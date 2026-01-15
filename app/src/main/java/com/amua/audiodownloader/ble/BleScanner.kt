package com.amua.audiodownloader.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BLE Scanner for discovering Amua devices.
 */
class BleScanner(private val context: Context) {

    companion object {
        private const val TAG = "BleScanner"
        private const val DEVICE_NAME_FILTER = "Amua"
    }

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bleScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<ScannedDevice>> = _discoveredDevices.asStateFlow()

    private val deviceMap = mutableMapOf<String, ScannedDevice>()

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val deviceName = device.name ?: result.scanRecord?.deviceName

            // Filter for Amua devices
            if (deviceName != null && deviceName.contains(DEVICE_NAME_FILTER, ignoreCase = true)) {
                val scannedDevice = ScannedDevice(
                    device = device,
                    name = deviceName,
                    address = device.address,
                    rssi = result.rssi
                )

                deviceMap[device.address] = scannedDevice
                _discoveredDevices.value = deviceMap.values.toList()

                Log.i(TAG, "Found device: $deviceName (${device.address}), RSSI: ${result.rssi}")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error code: $errorCode")
            _isScanning.value = false
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (_isScanning.value) {
            Log.w(TAG, "Scan already in progress")
            return
        }

        if (bleScanner == null) {
            Log.e(TAG, "BLE Scanner not available")
            return
        }

        // Clear previous results
        deviceMap.clear()
        _discoveredDevices.value = emptyList()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        // No filters - we filter in the callback by device name
        bleScanner.startScan(null, scanSettings, scanCallback)
        _isScanning.value = true
        Log.i(TAG, "Scan started")
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!_isScanning.value) {
            return
        }

        bleScanner?.stopScan(scanCallback)
        _isScanning.value = false
        Log.i(TAG, "Scan stopped")
    }

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true
}

/**
 * Represents a scanned BLE device.
 */
data class ScannedDevice(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val rssi: Int
)
