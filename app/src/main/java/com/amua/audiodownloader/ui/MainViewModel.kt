package com.amua.audiodownloader.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.amua.audiodownloader.audio.AudioDataHandler
import com.amua.audiodownloader.audio.WavFileWriter
import com.amua.audiodownloader.ble.AmuaBleManager
import com.amua.audiodownloader.ble.BleScanner
import com.amua.audiodownloader.ble.ScannedDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ViewModel for the main screen, managing BLE connection and audio streaming state.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
        // Skip first 2 seconds of data (as per record.py)
        private const val SKIP_INITIAL_MS = 2000L
    }

    // BLE components
    private val bleScanner = BleScanner(application)
    private val bleManager = AmuaBleManager(application)

    // Audio components
    private val audioDataHandler = AudioDataHandler()
    private val wavFileWriter = WavFileWriter(application)

    // UI State
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // Discovered devices
    val discoveredDevices: StateFlow<List<ScannedDevice>> = bleScanner.discoveredDevices
    val isScanning: StateFlow<Boolean> = bleScanner.isScanning

    // Streaming state
    private var streamStartTime: Long = 0
    private var isSkippingInitialData = false

    init {
        setupBleCallbacks()
    }

    private fun setupBleCallbacks() {
        bleManager.connectionStateCallback = { isConnected ->
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(
                    connectionState = if (isConnected) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED,
                    isStreaming = if (!isConnected) false else _uiState.value.isStreaming
                )
            }
        }

        bleManager.audioDataCallback = { data ->
            handleAudioData(data)
        }
    }

    private fun handleAudioData(data: ByteArray) {
        // Skip initial data (first 2 seconds) as per record.py
        if (isSkippingInitialData) {
            val elapsed = System.currentTimeMillis() - streamStartTime
            if (elapsed < SKIP_INITIAL_MS) {
                Log.d(TAG, "Skipping initial data, elapsed: ${elapsed}ms")
                return
            }
            isSkippingInitialData = false
            Log.i(TAG, "Initial skip period complete, now recording")
        }

        val samplesProcessed = audioDataHandler.processPacket(data)

        if (samplesProcessed > 0) {
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(
                    sampleCount = audioDataHandler.getSampleCount(),
                    packetCount = audioDataHandler.getPacketCount(),
                    recordingDuration = audioDataHandler.getDurationSeconds()
                )
            }
        }
    }

    // Scanning functions
    fun startScan() {
        if (!bleScanner.isBluetoothEnabled()) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Bluetooth is not enabled"
            )
            return
        }
        bleScanner.startScan()
    }

    fun stopScan() {
        bleScanner.stopScan()
    }

    // Connection functions
    fun selectDevice(device: ScannedDevice) {
        _uiState.value = _uiState.value.copy(selectedDevice = device)
    }

    fun connect() {
        val device = _uiState.value.selectedDevice ?: return

        _uiState.value = _uiState.value.copy(
            connectionState = ConnectionState.CONNECTING
        )

        stopScan()
        bleManager.connectToDevice(device.device)
    }

    fun disconnect() {
        if (_uiState.value.isStreaming) {
            stopStream()
        }
        bleManager.disconnectDevice()
    }

    // Streaming functions
    fun startStream() {
        if (_uiState.value.connectionState != ConnectionState.CONNECTED) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Not connected to device"
            )
            return
        }

        // Clear previous recording data
        audioDataHandler.clear()
        streamStartTime = System.currentTimeMillis()
        isSkippingInitialData = true

        _uiState.value = _uiState.value.copy(
            sampleCount = 0,
            packetCount = 0,
            recordingDuration = 0f
        )

        bleManager.startStream { success ->
            viewModelScope.launch {
                if (success) {
                    _uiState.value = _uiState.value.copy(isStreaming = true)
                } else {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Failed to start stream"
                    )
                }
            }
        }
    }

    fun stopStream() {
        bleManager.stopStream { success ->
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isStreaming = false)
                if (!success) {
                    Log.w(TAG, "Failed to send stop command")
                }
            }
        }
    }

    // Recording functions
    fun saveRecording(filename: String? = null): File? {
        val samples = audioDataHandler.getSamples()
        if (samples.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "No audio data to save"
            )
            return null
        }

        var savedFile: File? = null

        viewModelScope.launch {
            savedFile = withContext(Dispatchers.IO) {
                wavFileWriter.saveToFile(samples, filename = filename)
            }

            if (savedFile != null) {
                _uiState.value = _uiState.value.copy(
                    lastSavedFile = savedFile,
                    successMessage = "Saved to ${savedFile?.name}"
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to save recording"
                )
            }
        }

        return savedFile
    }

    fun clearRecording() {
        audioDataHandler.clear()
        _uiState.value = _uiState.value.copy(
            sampleCount = 0,
            packetCount = 0,
            recordingDuration = 0f
        )
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun clearSuccess() {
        _uiState.value = _uiState.value.copy(successMessage = null)
    }

    fun getRecordingsDirectory(): File = wavFileWriter.getOutputDirectory()

    override fun onCleared() {
        super.onCleared()
        bleScanner.stopScan()
        if (_uiState.value.isStreaming) {
            bleManager.stopStream(null)
        }
        bleManager.disconnectDevice()
    }
}

/**
 * Represents the connection state.
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

/**
 * UI state for the main screen.
 */
data class UiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val selectedDevice: ScannedDevice? = null,
    val isStreaming: Boolean = false,
    val sampleCount: Int = 0,
    val packetCount: Int = 0,
    val recordingDuration: Float = 0f,
    val lastSavedFile: File? = null,
    val errorMessage: String? = null,
    val successMessage: String? = null
)
