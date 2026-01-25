package com.amua.audiodownloader.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
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
import com.amua.audiodownloader.util.UpdateManager
import kotlinx.coroutines.launch

/**
 * Main activity for AmuaRecorder.
 * Handles BLE scanning, connection, streaming, and recording.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var deviceAdapter: DeviceAdapter
    private lateinit var updateManager: UpdateManager

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

        updateManager = UpdateManager(this)

        // Set up toolbar with menu
        setSupportActionBar(binding.toolbar)

        setupRecyclerView()
        setupButtons()
        observeState()

        // Check for updates on app launch
        checkForUpdates()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_check_updates -> {
                manualCheckForUpdates()
                true
            }
            R.id.action_about -> {
                showAboutDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showAboutDialog() {
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val versionName = packageInfo.versionName ?: "Unknown"
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }

        AlertDialog.Builder(this)
            .setTitle("AmuaRecorder")
            .setMessage(
                "Version: $versionName ($versionCode)\n\n" +
                "Audio recording app for Amua devices.\n\n" +
                "© Amua Innovations"
            )
            .setPositiveButton("OK", null)
            .show()
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

        binding.discardButton.setOnClickListener {
            showDiscardConfirmation()
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

        // Enable save/discard buttons if we have recorded data
        val hasRecording = state.sampleCount > 0 && !state.isStreaming
        binding.saveButton.isEnabled = hasRecording
        binding.discardButton.isEnabled = hasRecording
    }

    private var lastSessionId: String? = null
    private var cachedSessionInfo: String = ""
    private var lastSavedUri: Uri? = null

    private fun updateSessionUi(state: UiState) {
        state.currentSession?.let { session ->
            binding.sessionNameText.text = session.name

            // Check if we need to refresh the cache:
            // - Session changed
            // - A new recording was saved (lastSavedUri changed)
            // - Cache is empty
            // But never query MediaStore while streaming (too expensive)
            val needsRefresh = session.id != lastSessionId ||
                    state.lastSavedUri != lastSavedUri ||
                    cachedSessionInfo.isEmpty()

            if (!state.isStreaming && needsRefresh) {
                lastSessionId = session.id
                lastSavedUri = state.lastSavedUri
                val count = session.getRecordingCount(this)
                cachedSessionInfo = "$count recording${if (count != 1) "s" else ""} • ${session.getFormattedSize(this)}"
            }
            binding.sessionInfoText.text = cachedSessionInfo
        } ?: run {
            binding.sessionNameText.text = "No session"
            binding.sessionInfoText.text = ""
            lastSessionId = null
            cachedSessionInfo = ""
            lastSavedUri = null
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
            .show()
    }

    private fun showDiscardConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Discard Recording")
            .setMessage("Discard ${viewModel.uiState.value.sampleCount} samples? This cannot be undone.")
            .setPositiveButton("Discard") { _, _ ->
                viewModel.clearRecording()
                Toast.makeText(this, "Recording discarded", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
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

    override fun onDestroy() {
        super.onDestroy()
        updateManager.cleanup()
    }

    private fun checkForUpdates() {
        lifecycleScope.launch {
            val result = updateManager.checkForUpdates(isManualCheck = false)
            if (result.updateInfo != null) {
                showUpdateDialog(result.updateInfo)
            }
        }
    }

    private fun manualCheckForUpdates() {
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "Checking for updates...", Toast.LENGTH_SHORT).show()

            val result = updateManager.checkForUpdates(isManualCheck = true)

            when {
                result.wasRateLimited -> {
                    Toast.makeText(
                        this@MainActivity,
                        "Please wait ${result.minutesUntilNextCheck} minutes before checking again",
                        Toast.LENGTH_LONG
                    ).show()
                }
                result.updateInfo != null -> {
                    showUpdateDialog(result.updateInfo)
                }
                else -> {
                    Toast.makeText(
                        this@MainActivity,
                        "You're on the latest version",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun showUpdateDialog(updateInfo: UpdateManager.UpdateInfo) {
        val sizeText = updateManager.formatSize(updateInfo.apkSize)

        AlertDialog.Builder(this)
            .setTitle("Update Available")
            .setMessage(
                "A new version of AmuaRecorder is available!\n\n" +
                "Version: ${updateInfo.versionName}\n" +
                "Size: $sizeText\n\n" +
                "What's new:\n${updateInfo.releaseNotes.take(500)}"
            )
            .setPositiveButton("Update Now") { _, _ ->
                startUpdate(updateInfo)
            }
            .setNegativeButton("Later", null)
            .setNeutralButton("Skip Version") { _, _ ->
                updateManager.skipVersion(updateInfo.versionName)
            }
            .setCancelable(true)
            .show()
    }

    private fun startUpdate(updateInfo: UpdateManager.UpdateInfo) {
        // Check if we can install packages
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                // Request permission to install packages
                AlertDialog.Builder(this)
                    .setTitle("Permission Required")
                    .setMessage("To install updates, please allow AmuaRecorder to install apps from this source.")
                    .setPositiveButton("Open Settings") { _, _ ->
                        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                return
            }
        }

        Toast.makeText(this, "Downloading update...", Toast.LENGTH_SHORT).show()

        updateManager.downloadAndInstall(
            updateInfo,
            onComplete = { success ->
                runOnUiThread {
                    if (!success) {
                        Toast.makeText(this, "Download failed. Please try again.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }
}
