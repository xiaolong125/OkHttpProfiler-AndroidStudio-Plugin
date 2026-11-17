package com.itkacher.data

import com.android.ddmlib.IDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.DefaultListCellRenderer
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.SwingUtilities

class DebugDeviceTest {
    @Test
    fun `rendering a device never queries ADB on the event thread`() {
        val device = fakeDevice()
        val snapshot = DebugDevice.load(device)
        assertEquals("serial-123", snapshot.serialNumber)
        assertEquals("pixel_6", snapshot.legacyName)
        SwingUtilities.invokeAndWait {
            val renderer = DefaultListCellRenderer()
            repeat(100) {
                val label = renderer.getListCellRendererComponent(JList<Any>(), snapshot, 0, false, false) as JLabel
                assertEquals("Pixel 6 Android 16, API 36", label.text)
            }
        }
    }

    @Test
    fun `offline property failures fall back to the serial number`() {
        val snapshot = DebugDevice.load(fakeDevice(offline = true))
        SwingUtilities.invokeAndWait {
            assertEquals("serial-123", snapshot.toString())
        }
    }

    @Test
    fun `emulators keep their AVD name in the cached label`() {
        val snapshot = DebugDevice.load(fakeDevice(emulator = true))
        assertEquals("Emulator Test AVD Android 16, API 36", snapshot.toString())
    }

    @Test
    fun `loading properties on the event thread is rejected before any device access`() {
        val rejected = AtomicBoolean()
        SwingUtilities.invokeAndWait {
            try {
                DebugDevice.load(fakeDevice())
            } catch (_: IllegalStateException) {
                rejected.set(true)
            }
        }
        assertTrue(rejected.get())
    }

    private fun fakeDevice(offline: Boolean = false, emulator: Boolean = false): IDevice =
        Proxy.newProxyInstance(IDevice::class.java.classLoader, arrayOf(IDevice::class.java)) { _, method, args ->
            // Fail deterministically instead of letting the regression hang the test's EDT.
            check(!SwingUtilities.isEventDispatchThread()) { "ADB access on EDT: ${method.name}" }
            when (method.name) {
                "getSerialNumber" -> "serial-123"
                "getName" -> {
                    if (offline) throw IllegalStateException("Device offline")
                    "pixel_6"
                }
                "isEmulator" -> emulator
                "getAvdName" -> "Test AVD"
                "getProperty" -> when (args?.first()) {
                    IDevice.PROP_BUILD_VERSION -> "16"
                    IDevice.PROP_BUILD_API_LEVEL -> "36"
                    else -> error("Unexpected property")
                }
                else -> error("Unexpected device call: ${method.name}")
            }
        } as IDevice
}
