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
package com.itkacher.data

import com.android.ddmlib.IDevice
import com.itkacher.extension.capitalizeFirst
import com.itkacher.extension.safe

/** A snapshot: Swing renderers must never query ADB while painting. */
data class DebugDevice(
    val device: IDevice,
    val serialNumber: String,
    val legacyName: String?,
    private val displayName: String
) {
    override fun toString(): String = displayName

    companion object {
        /** Must run on a background thread: even IDevice.name may wait for properties. */
        fun load(device: IDevice): DebugDevice {
            check(!javax.swing.SwingUtilities.isEventDispatchThread())
            val serial = device.serialNumber
            return try {
                val name = device.name
                val label = if (device.isEmulator) {
                    "Emulator ${device.avdName.safe()}"
                } else {
                    name.safe().replace("_", " ").take(20).capitalizeFirst()
                }
                DebugDevice(
                    device, serial, name,
                    "$label Android ${device.getProperty(IDevice.PROP_BUILD_VERSION).safe()}, API ${device.getProperty(IDevice.PROP_BUILD_API_LEVEL).safe()}"
                )
            } catch (_: Exception) {
                // Offline/disconnected devices still need a usable, I/O-free label.
                DebugDevice(device, serial, null, serial)
            }
        }
    }
}
