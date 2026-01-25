package com.amua.audiodownloader.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.data.Data
import java.util.UUID

/**
 * BLE Manager for Amua audio streaming device.
 * Handles connection, characteristic discovery, and data streaming.
 */
class AmuaBleManager(context: Context) : BleManager(context) {

    companion object {
        private const val TAG = "AmuaBleManager"

        // Audio characteristic UUID from the embedded device
        val AUDIO_CHAR_UUID: UUID = UUID.fromString("AC570007-D569-BFA9-2713-0000E3F7F6F4")

        // Commands
        const val CMD_START_STREAM: Byte = 0x01
        const val CMD_STOP_STREAM: Byte = 0x00
    }

    private var audioCharacteristic: BluetoothGattCharacteristic? = null

    // Callback for receiving audio data
    var audioDataCallback: ((ByteArray) -> Unit)? = null

    // Callback for connection state changes
    var connectionStateCallback: ((Boolean) -> Unit)? = null

    override fun log(priority: Int, message: String) {
        Log.println(priority, TAG, message)
    }

    override fun getMinLogPriority(): Int = Log.DEBUG

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        // Search for the audio characteristic in all services
        for (service in gatt.services) {
            val characteristic = service.getCharacteristic(AUDIO_CHAR_UUID)
            if (characteristic != null) {
                val properties = characteristic.properties
                val hasWrite = (properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                        (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                val hasNotify = (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0

                if (hasWrite && hasNotify) {
                    audioCharacteristic = characteristic
                    Log.i(TAG, "Found audio characteristic in service: ${service.uuid}")
                    return true
                }
            }
        }
        Log.e(TAG, "Audio characteristic not found")
        return false
    }

    override fun initialize() {
        audioCharacteristic?.let { char ->
            // Enable notifications for the audio characteristic
            setNotificationCallback(char).with { _, data ->
                data.value?.let { bytes ->
                    Log.d(TAG, "Received ${bytes.size} bytes")
                    audioDataCallback?.invoke(bytes)
                }
            }

            enableNotifications(char)
                .done { Log.i(TAG, "Notifications enabled") }
                .fail { _, status -> Log.e(TAG, "Failed to enable notifications: $status") }
                .enqueue()
        }
    }

    override fun onServicesInvalidated() {
        audioCharacteristic = null
    }

    /**
     * Send START command to begin audio streaming.
     */
    fun startStream(callback: ((Boolean) -> Unit)? = null) {
        audioCharacteristic?.let { char ->
            writeCharacteristic(char, byteArrayOf(CMD_START_STREAM), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                .done {
                    Log.i(TAG, "Start stream command sent")
                    callback?.invoke(true)
                }
                .fail { _, status ->
                    Log.e(TAG, "Failed to send start command: $status")
                    callback?.invoke(false)
                }
                .enqueue()
        } ?: run {
            Log.e(TAG, "Audio characteristic not available")
            callback?.invoke(false)
        }
    }

    /**
     * Send STOP command to stop audio streaming.
     */
    fun stopStream(callback: ((Boolean) -> Unit)? = null) {
        audioCharacteristic?.let { char ->
            writeCharacteristic(char, byteArrayOf(CMD_STOP_STREAM), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                .done {
                    Log.i(TAG, "Stop stream command sent")
                    callback?.invoke(true)
                }
                .fail { _, status ->
                    Log.e(TAG, "Failed to send stop command: $status")
                    callback?.invoke(false)
                }
                .enqueue()
        } ?: run {
            Log.e(TAG, "Audio characteristic not available")
            callback?.invoke(false)
        }
    }

    /**
     * Connect to a BLE device.
     */
    fun connectToDevice(device: BluetoothDevice) {
        connect(device)
            .retry(3, 200)
            .useAutoConnect(false)
            .timeout(30000)
            .done {
                Log.i(TAG, "Connected to ${device.address}")
                connectionStateCallback?.invoke(true)
            }
            .fail { _, status ->
                Log.e(TAG, "Connection failed: $status")
                connectionStateCallback?.invoke(false)
            }
            .enqueue()
    }

    /**
     * Disconnect from the current device.
     */
    fun disconnectDevice() {
        disconnect()
            .done {
                Log.i(TAG, "Disconnected")
                connectionStateCallback?.invoke(false)
            }
            .enqueue()
    }
}
