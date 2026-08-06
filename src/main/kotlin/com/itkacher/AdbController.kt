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
import java.util.concurrent.atomic.AtomicLong
import javax.swing.DefaultComboBoxModel
import javax.swing.SwingUtilities

class AdbController(val mainForm: MainForm, project: Project, val preferences: PluginPreferences, toolWindow: ToolWindow) {

    private var logCatTask: LogCatReceiverTask? = null
    private var logCatThread: Thread? = null
    private var logcatLineCount = 0L
    private val logcatSession = AtomicLong()

    private var selectedDevice: IDevice? = null
    private var selectedProcess: DebugProcess? = null

    val requestTableController = FormViewController(mainForm, preferences, project)

    private val executor = Executors.newFixedThreadPool(1)

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
        Disposer.register(toolWindow.contentManager) {
            stopLogCat()
            executor.shutdownNow()
        }
        initDeviceList(project)
    }

    private fun initDeviceList(project: Project) {
        AndroidDebugBridge.addDeviceChangeListener(object : AndroidDebugBridge.IDeviceChangeListener {
            override fun deviceChanged(device: IDevice?, p1: Int) {
                log("deviceChanged $device")
                device?.let {
                    attachToDevice(device)
                }
            }

            override fun deviceConnected(device: IDevice?) {
                log("deviceConnected $device")
                updateDeviceList(AndroidDebugBridge.getBridge()?.devices)
            }

            override fun deviceDisconnected(device: IDevice?) {
                log("deviceDisconnected $device")
                updateDeviceList(AndroidDebugBridge.getBridge()?.devices)
            }
        })
        AndroidDebugBridge.addDebugBridgeChangeListener {
            val devices = it?.devices
            if (devices?.isNotEmpty() == true) {
                log("addDebugBridgeChangeListener $it")
                updateDeviceList(devices)
            } else {
                log("addDebugBridgeChangeListener EMPTY $it and connected ${it?.isConnected}")
            }
        }
        AndroidDebugBridge.addClientChangeListener { client: Client?, _: Int ->
            updateClient(client)
        }
        val bridge0: AndroidDebugBridge? = AndroidSdkUtils.getDebugBridge(project)
        log("initDeviceList bridge0 ${bridge0?.isConnected}")
    }

    private fun updateClient(client: Client?) {
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
        log("updateDeviceList ${devices?.size}")
        val selectedDeviceName = preferences.getSelectedDevice()
        var selectedDevice: IDevice? = null
        if (devices != null) {
            mainForm.mainContainer.isVisible = true
            val debugDevices = ArrayList<DebugDevice>()
            for (device in devices) {
                val debugDevice = DebugDevice(device)
                if (device.name == selectedDeviceName) {
                    selectedDevice = device
                }
                debugDevices.add(debugDevice)
            }
            val model = DefaultComboBoxModel<DebugDevice>(debugDevices.toTypedArray())
            val list = mainForm.deviceList
            list.model = model
            list.addItemListener {
                if (it.stateChange == ItemEvent.SELECTED) {
                    log("Selected ${list.selectedItem}")
                    val device = list.selectedItem as DebugDevice
                    requestTableController.clear()
                    attachToDevice(device.device)
                    preferences.setSelectedDevice(device.device.name)
                }
            }
            if (selectedDevice != null) {
                attachToDevice(selectedDevice)
            } else {
                devices.firstOrNull()?.let {
                    attachToDevice(it)
                }
            }
        } else {
            mainForm.mainContainer.isVisible = false
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
        mainForm.appList.model = model
        mainForm.appList.addItemListener {
            if (it.stateChange == ItemEvent.SELECTED) {
                val client = mainForm.appList.selectedItem as DebugProcess
                preferences.setSelectedProcessPackage(client.getClientKey())
                defaultSelection = client
                selectedProcess = client
                log("selectedProcess $defaultSelection")
                requestTableController.clear()
//                requestTableController.addAll(RequestDataSource.getRequestList(client.getClientKey()))
            }
        }
        if (defaultSelection != null) {
            mainForm.appList.selectedItem = defaultSelection
            selectedProcess = defaultSelection
        } else {
            selectedProcess = debugProcessList.firstOrNull()
        }
    }


    private fun log(text: String) {
        LOG.warn(text)
    }

    private fun setListener(device: IDevice) {
        log(device.toString())
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
