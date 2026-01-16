package com.amua.audiodownloader.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.amua.audiodownloader.R
import com.amua.audiodownloader.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

/**
 * Main activity for the Amua Audio Downloader app.
 * Handles BLE scanning, connection, streaming, and recording.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var deviceAdapter: DeviceAdapter

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            viewModel.startScan()
        } else {
            Toast.makeText(
                this,
                "Bluetooth permissions are required for scanning",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupButtons()
        observeState()
    }

    private fun setupRecyclerView() {
        deviceAdapter = DeviceAdapter { device ->
            viewModel.selectDevice(device)
        }
        binding.deviceList.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = deviceAdapter
        }
    }

    private fun setupButtons() {
        binding.scanButton.setOnClickListener {
            if (viewModel.isScanning.value) {
                viewModel.stopScan()
            } else {
                startScanWithPermissionCheck()
            }
        }

        binding.connectButton.setOnClickListener {
            when (viewModel.uiState.value.connectionState) {
                ConnectionState.DISCONNECTED -> viewModel.connect()
                ConnectionState.CONNECTED -> viewModel.disconnect()
                ConnectionState.CONNECTING -> { /* Do nothing while connecting */ }
            }
        }

        binding.streamButton.setOnClickListener {
            if (viewModel.uiState.value.isStreaming) {
                viewModel.stopStream()
            } else {
                viewModel.startStream()
            }
        }

        binding.saveButton.setOnClickListener {
            showSaveDialog()
        }

        binding.sessionsButton.setOnClickListener {
            startActivity(Intent(this, SessionsActivity::class.java))
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe discovered devices
                launch {
                    viewModel.discoveredDevices.collect { devices ->
                        deviceAdapter.submitList(devices)
                    }
                }

                // Observe scanning state
                launch {
                    viewModel.isScanning.collect { isScanning ->
                        binding.scanButton.text = if (isScanning) {
                            getString(R.string.stop_scan)
                        } else {
                            getString(R.string.scan)
                        }
                    }
                }

                // Observe UI state
                launch {
                    viewModel.uiState.collect { state ->
                        updateConnectionUi(state)
                        updateStreamingUi(state)
                        updateRecordingStats(state)
                        updateSessionUi(state)
                        handleMessages(state)
                    }
                }

                // Update selected device in adapter
                launch {
                    viewModel.uiState.collect { state ->
                        deviceAdapter.setSelectedDevice(state.selectedDevice?.address)
                    }
                }
            }
        }
    }

    private fun updateConnectionUi(state: UiState) {
        // Update status text
        binding.statusLabel.text = when (state.connectionState) {
            ConnectionState.DISCONNECTED -> getString(R.string.status_disconnected)
            ConnectionState.CONNECTING -> getString(R.string.status_connecting)
            ConnectionState.CONNECTED -> {
                if (state.isStreaming) {
                    getString(R.string.status_streaming)
                } else {
                    getString(R.string.status_connected)
                }
            }
        }

        // Update status color
        binding.statusLabel.setTextColor(
            when {
                state.isStreaming -> getColor(R.color.streaming)
                state.connectionState == ConnectionState.CONNECTED -> getColor(R.color.success)
                else -> getColor(R.color.text_primary)
            }
        )

        // Update device name text
        binding.deviceNameText.text = state.selectedDevice?.let {
            "${it.name} (${it.address})"
        } ?: getString(R.string.no_device_selected)

        // Update connect button
        binding.connectButton.isEnabled = state.selectedDevice != null &&
                state.connectionState != ConnectionState.CONNECTING
        binding.connectButton.text = when (state.connectionState) {
            ConnectionState.DISCONNECTED -> getString(R.string.connect)
            ConnectionState.CONNECTING -> getString(R.string.status_connecting)
            ConnectionState.CONNECTED -> getString(R.string.disconnect)
        }

        // Update stream button
        binding.streamButton.isEnabled = state.connectionState == ConnectionState.CONNECTED
    }

    private fun updateStreamingUi(state: UiState) {
        binding.streamButton.text = if (state.isStreaming) {
            getString(R.string.stop_stream)
        } else {
            getString(R.string.start_stream)
        }
    }

    private fun updateRecordingStats(state: UiState) {
        binding.sampleCountText.text = state.sampleCount.toString()
        binding.packetCountText.text = state.packetCount.toString()
        binding.durationText.text = String.format("%.1f s", state.recordingDuration)

        // Enable save button if we have recorded data
        binding.saveButton.isEnabled = state.sampleCount > 0 && !state.isStreaming
    }

    private fun updateSessionUi(state: UiState) {
        state.currentSession?.let { session ->
            binding.sessionNameText.text = session.name
            val count = session.getRecordingCount()
            binding.sessionInfoText.text = "$count recording${if (count != 1) "s" else ""} • ${session.getFormattedSize()}"
        } ?: run {
            binding.sessionNameText.text = "No session"
            binding.sessionInfoText.text = ""
        }
    }

    private fun handleMessages(state: UiState) {
        state.errorMessage?.let { message ->
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            viewModel.clearError()
        }

        state.successMessage?.let { message ->
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            viewModel.clearSuccess()
        }
    }

    private fun showSaveDialog() {
        val editText = EditText(this).apply {
            hint = "Enter filename (optional)"
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Save Recording")
            .setMessage("Save ${viewModel.uiState.value.sampleCount} samples to WAV file?")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                val filename = editText.text.toString().takeIf { it.isNotBlank() }
                viewModel.saveRecording(filename)
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Clear") { _, _ ->
                viewModel.clearRecording()
            }
            .show()
    }

    private fun startScanWithPermissionCheck() {
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            viewModel.startScan()
        } else {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh session info when returning from SessionsActivity
        viewModel.refreshSession()
    }

    override fun onStop() {
        super.onStop()
        viewModel.stopScan()
    }
}
