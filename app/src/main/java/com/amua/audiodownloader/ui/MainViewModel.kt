package com.amua.audiodownloader.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.amua.audiodownloader.audio.AudioDataHandler
import com.amua.audiodownloader.audio.StreamingWavWriter
import com.amua.audiodownloader.ble.AmuaBleManager
import com.amua.audiodownloader.ble.BleScanner
import com.amua.audiodownloader.ble.ScannedDevice
import com.amua.audiodownloader.session.Session
import com.amua.audiodownloader.session.SessionManager
import com.amua.audiodownloader.util.MediaStoreManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    // Session and storage management
    private val sessionManager = SessionManager(application)
    private val mediaStoreManager = MediaStoreManager(application)

    // Temp directory for recording before saving to MediaStore
    private val tempRecordingDir: File = File(application.cacheDir, "recordings").apply {
        if (!exists()) mkdirs()
    }

    // UI State
    private val _uiState = MutableStateFlow(UiState(
        currentSession = sessionManager.getCurrentSession()
    ))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // Discovered devices
    val discoveredDevices: StateFlow<List<ScannedDevice>> = bleScanner.discoveredDevices
    val isScanning: StateFlow<Boolean> = bleScanner.isScanning

    // Streaming state
    private var streamStartTime: Long = 0
    private var isSkippingInitialData = false
    private var streamingWriter: StreamingWavWriter? = null
    private var currentRecordingFile: File? = null
    private var currentRecordingFilename: String? = null

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

        // Set up streaming writer to temp directory
        val filename = generateFilename()
        currentRecordingFilename = filename
        currentRecordingFile = File(tempRecordingDir, "$filename.wav")
        streamingWriter = StreamingWavWriter(currentRecordingFile!!).also { writer ->
            if (!writer.open()) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to create recording file"
                )
                return
            }
        }

        // Set up callback to stream samples directly to file
        audioDataHandler.setSampleCallback { samples ->
            streamingWriter?.writeSamples(samples)
        }

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
                    // Clean up streaming writer on failure
                    streamingWriter?.close()
                    streamingWriter = null
                    audioDataHandler.setSampleCallback(null)
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Failed to start stream"
                    )
                }
            }
        }
    }

    private fun generateFilename(): String {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return "amua_recording_${dateFormat.format(Date())}"
    }

    fun stopStream() {
        bleManager.stopStream { success ->
            viewModelScope.launch {
                // Close streaming writer and finalize the WAV file
                audioDataHandler.setSampleCallback(null)
                streamingWriter?.close()
                streamingWriter = null

                _uiState.value = _uiState.value.copy(isStreaming = false)
                if (!success) {
                    Log.w(TAG, "Failed to send stop command")
                }
            }
        }
    }

    // Recording functions
    fun saveRecording(customFilename: String? = null) {
        val tempFile = currentRecordingFile

        if (tempFile == null || !tempFile.exists() || audioDataHandler.getSampleCount() == 0) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "No audio data to save"
            )
            return
        }

        val session = sessionManager.getCurrentSession()
        val displayName = if (!customFilename.isNullOrBlank()) {
            "${customFilename.trim()}.wav"
        } else {
            "${currentRecordingFilename}.wav"
        }

        // Save to MediaStore in background
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) {
                mediaStoreManager.saveToMediaStore(tempFile, session.folderName, displayName)
            }

            if (uri != null) {
                // Delete temp file after successful save
                withContext(Dispatchers.IO) {
                    tempFile.delete()
                }
                currentRecordingFile = null
                currentRecordingFilename = null

                // Reset recording stats
                audioDataHandler.clear()

                _uiState.value = _uiState.value.copy(
                    lastSavedUri = uri,
                    successMessage = "Saved to Music/AmuaRecordings/${session.folderName}/",
                    currentSession = sessionManager.getCurrentSession(),
                    sampleCount = 0,
                    packetCount = 0,
                    recordingDuration = 0f
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to save recording"
                )
            }
        }
    }

    // Session functions
    fun createNewSession() {
        val newSession = sessionManager.createNewSession()
        _uiState.value = _uiState.value.copy(
            currentSession = newSession,
            successMessage = "New session started"
        )
    }

    fun refreshSession() {
        _uiState.value = _uiState.value.copy(
            currentSession = sessionManager.getCurrentSession()
        )
    }

    fun clearRecording() {
        // Clean up streaming writer if active
        audioDataHandler.setSampleCallback(null)
        streamingWriter?.close()
        streamingWriter = null

        // Delete the current temp recording file if it exists
        currentRecordingFile?.let { file ->
            if (file.exists()) {
                file.delete()
            }
        }
        currentRecordingFile = null
        currentRecordingFilename = null

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

    override fun onCleared() {
        super.onCleared()
        bleScanner.stopScan()
        if (_uiState.value.isStreaming) {
            bleManager.stopStream(null)
        }
        // Clean up streaming writer
        audioDataHandler.setSampleCallback(null)
        streamingWriter?.close()
        streamingWriter = null
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
    val lastSavedUri: Uri? = null,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val currentSession: Session? = null
)
