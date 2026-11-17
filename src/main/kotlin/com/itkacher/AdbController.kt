/**
 * Copyright 2018 LocaleBro.com [Ievgenii Tkachenko(gektor650@gmail.com)]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.itkacher

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.Client
import com.android.ddmlib.IDevice
import com.android.ddmlib.logcat.LogCatListener
import com.android.ddmlib.logcat.LogCatReceiverTask
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.itkacher.data.DebugDevice
import com.itkacher.data.DebugProcess
import com.itkacher.data.ProfilerLogMessage
import com.itkacher.data.RequestDataSource
import com.itkacher.views.form.MainForm
import org.jetbrains.android.sdk.AndroidSdkUtils
import java.awt.event.ItemEvent
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.DefaultComboBoxModel
import javax.swing.SwingUtilities

class AdbController(val mainForm: MainForm, project: Project, val preferences: PluginPreferences, toolWindow: ToolWindow) {

    private var logCatTask: LogCatReceiverTask? = null
    private var logCatThread: Thread? = null
    private var logcatLineCount = 0L
    private val logcatSession = AtomicLong()

    private var selectedDevice: IDevice? = null
    @Volatile private var selectedProcess: DebugProcess? = null

    val requestTableController = FormViewController(mainForm, preferences, project)

    private val executor = Executors.newFixedThreadPool(1)
    private val deviceExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "okprofiler-devices").apply { isDaemon = true }
    }
    private val disposed = AtomicBoolean()
    private val deviceListVersion = AtomicLong()
    private var updatingDeviceList = false
    private var updatingProcessList = false

    private fun createDeviceListener(session: Long) = LogCatListener { messages ->
        executor.execute {
            if (session != logcatSession.get()) {
                return@execute
            }
            val selected = selectedProcess
            if (logcatLineCount == 0L) {
                log("logcat: first batch received, ${messages.size} lines, selectedPid=${selected?.pid}")
            }
            logcatLineCount += messages.size
            if (selected == null) {
                log("logcat: ${messages.size} lines but NO selected process")
                return@execute
            }
            for (line in messages) {
                if (session != logcatSession.get()) {
                    return@execute
                }
                val tag = line.header.tag
                val profilerMessage = ProfilerLogMessage.parse(tag) ?: continue
                log("Profiler tag='$tag' linePid=${line.header.pid} selectedPid=${selected.pid} msgLen=${line.message.length}")
                if (selected.pid != line.header.pid) {
                    log("  -> skipped: pid mismatch")
                    continue
                }
                val debugRequest = RequestDataSource.getRequestFromMessage(
                    profilerMessage.requestId,
                    profilerMessage.type,
                    line.message
                )
                log("  -> id=${profilerMessage.requestId} type=${profilerMessage.type} request=${debugRequest != null}")
                if (debugRequest != null) {
                    try {
                        SwingUtilities.invokeLater {
                            if (session == logcatSession.get()) {
                                requestTableController.insertOrUpdate(debugRequest)
                            }
                        }
                    } catch (e: Exception) {
                        log("  -> insertOrUpdate failed: ${e.message}")
                    }
                }
            }
        }
    }

    init {
        // Register once; model refreshes must not accumulate selection listeners.
        mainForm.deviceList.addItemListener {
            if (it.stateChange == ItemEvent.SELECTED && !updatingDeviceList && !disposed.get()) {
                val device = mainForm.deviceList.selectedItem as? DebugDevice ?: return@addItemListener
                requestTableController.clear()
                preferences.setSelectedDevice(device.serialNumber)
                attachToDevice(device.device)
            }
        }
        mainForm.appList.addItemListener {
            if (it.stateChange == ItemEvent.SELECTED && !updatingProcessList && !disposed.get()) {
                val process = mainForm.appList.selectedItem as? DebugProcess ?: return@addItemListener
                preferences.setSelectedProcessPackage(process.getClientKey())
                selectedProcess = process
                requestTableController.clear()
            }
        }
        Disposer.register(toolWindow.contentManager) {
            disposed.set(true)
            deviceListVersion.incrementAndGet()
            stopLogCat()
            deviceExecutor.shutdownNow()
            executor.shutdownNow()
        }
        initDeviceList(project, toolWindow)
    }

    private fun initDeviceList(project: Project, toolWindow: ToolWindow) {
        val deviceListener = object : AndroidDebugBridge.IDeviceChangeListener {
            override fun deviceChanged(device: IDevice?, changeMask: Int) {
                updateDeviceList(AndroidDebugBridge.getBridge()?.devices)
            }

            override fun deviceConnected(device: IDevice?) {
                updateDeviceList(AndroidDebugBridge.getBridge()?.devices)
            }

            override fun deviceDisconnected(device: IDevice?) {
                updateDeviceList(AndroidDebugBridge.getBridge()?.devices)
            }
        }
        val bridgeListener = AndroidDebugBridge.IDebugBridgeChangeListener {
            updateDeviceList(it?.devices)
        }
        val clientListener = AndroidDebugBridge.IClientChangeListener { client: Client?, _: Int ->
            updateClient(client)
        }
        AndroidDebugBridge.addDeviceChangeListener(deviceListener)
        AndroidDebugBridge.addDebugBridgeChangeListener(bridgeListener)
        AndroidDebugBridge.addClientChangeListener(clientListener)
        Disposer.register(toolWindow.contentManager) {
            AndroidDebugBridge.removeDeviceChangeListener(deviceListener)
            AndroidDebugBridge.removeDebugBridgeChangeListener(bridgeListener)
            AndroidDebugBridge.removeClientChangeListener(clientListener)
        }
        // Getting the initial bridge can also wait for ADB startup.
        deviceExecutor.execute {
            if (!disposed.get()) {
                val bridge = AndroidSdkUtils.getDebugBridge(project)
                updateDeviceList(bridge?.devices)
            }
        }
    }

    private fun updateClient(client: Client?) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { updateClient(client) }
            return
        }
        if (disposed.get()) return
        val prefSelectedPackage = preferences.getSelectedProcessPackage()
        val clientData = client?.clientData
        val clientModel = mainForm.appList.model
        if (clientData != null && clientModel != null) {
            for (i in 0 until clientModel.size) {
                val model = clientModel.getElementAt(i)
                if (model.pid == clientData.pid) {
                    log("updateClient ${clientData.pid}")
                    model.packageName = clientData.packageName
                    model.clientDescription = clientData.clientDescription
                    if (model.getClientKey() == prefSelectedPackage) {
                        mainForm.appList.selectedItem = model
                        selectedProcess = model
                    }
                    break
                }
            }
            mainForm.appList.revalidate()
            mainForm.appList.repaint()
        }
    }

    private fun updateDeviceList(devices: Array<IDevice>?) {
        if (disposed.get()) return
        val version = deviceListVersion.incrementAndGet()
        val snapshot = devices?.clone().orEmpty()
        try {
            deviceExecutor.execute {
                if (disposed.get() || version != deviceListVersion.get()) return@execute
                val debugDevices = ArrayList<DebugDevice>(snapshot.size)
                for (device in snapshot) {
                    if (disposed.get() || version != deviceListVersion.get()) return@execute
                    debugDevices.add(DebugDevice.load(device))
                }
                SwingUtilities.invokeLater {
                    if (disposed.get() || version != deviceListVersion.get()) return@invokeLater
                    val preferred = preferences.getSelectedDevice()
                    val currentSerial = (mainForm.deviceList.selectedItem as? DebugDevice)?.serialNumber
                    val selected = debugDevices.firstOrNull { it.serialNumber == currentSerial }
                        ?: debugDevices.firstOrNull { it.serialNumber == preferred || it.legacyName == preferred }
                        ?: debugDevices.firstOrNull()
                    updatingDeviceList = true
                    try {
                        mainForm.deviceList.model = DefaultComboBoxModel(debugDevices.toTypedArray())
                        mainForm.deviceList.selectedItem = selected
                    } finally {
                        updatingDeviceList = false
                    }
                    mainForm.mainContainer.isVisible = debugDevices.isNotEmpty()
                    if (selected != null) {
                        preferences.setSelectedDevice(selected.serialNumber)
                        attachToDevice(selected.device)
                    } else {
                        stopLogCat()
                        selectedDevice = null
                        selectedProcess = null
                        mainForm.appList.model = DefaultComboBoxModel()
                    }
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            if (!disposed.get()) throw e
        }
    }

    private fun attachToDevice(device: IDevice) {
        createProcessList(device)
        setListener(device)
    }

    private fun createProcessList(device: IDevice) {
        val prefSelectedPackage = preferences.getSelectedProcessPackage()
        var defaultSelection: DebugProcess? = null
        val debugProcessList = ArrayList<DebugProcess>()
        log("createProcessList ${device.clients.size}")
        for (client in device.clients) {
            val clientData = client.clientData
            val process = DebugProcess(
                    clientData.pid,
                    clientData.packageName,
                    clientData.clientDescription
            )
            if (prefSelectedPackage == process.getClientKey()) {
                defaultSelection = process
            }
            log("addClient $process")
            debugProcessList.add(process)
        }
        val model = DefaultComboBoxModel<DebugProcess>(debugProcessList.toTypedArray())
        updatingProcessList = true
        try {
            mainForm.appList.model = model
            mainForm.appList.selectedItem = defaultSelection ?: debugProcessList.firstOrNull()
            selectedProcess = mainForm.appList.selectedItem as? DebugProcess
        } finally {
            updatingProcessList = false
        }
    }


    private fun log(text: String) {
        LOG.warn(text)
    }

    private fun setListener(device: IDevice) {
        log("attach device ${device.serialNumber}")
        if (selectedDevice == device && logCatTask != null) {
            return
        }
        stopLogCat()
        requestTableController.clear()
        val session = logcatSession.incrementAndGet()
        val task = LogCatReceiverTask(device)
        task.addLogCatListener(createDeviceListener(session))
        logCatTask = task
        logcatLineCount = 0L
        logCatThread = Thread({
            log("logcat task started for ${device.serialNumber}")
            try {
                task.run()
                log("logcat task finished (run returned) for ${device.serialNumber}")
            } catch (t: Throwable) {
                LOG.warn("logcat task CRASHED for ${device.serialNumber}", t)
            }
        }, "okprofiler-logcat-${device.serialNumber}").apply {
            isDaemon = true
            start()
        }
        selectedDevice = device
        val clients = device.clients
        if (clients != null) {
            for (client in clients) {
                updateClient(client)
            }
        }
    }

    private fun stopLogCat() {
        logcatSession.incrementAndGet()
        logCatTask?.stop()
        logCatTask = null
        logCatThread?.interrupt()
        logCatThread = null
    }

    companion object {
        private val LOG = Logger.getInstance(AdbController::class.java)
        const val STRING_BUNDLE = "strings"
    }
}
