package net.re22.androidttsserver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var statusText: TextView
    private lateinit var endpointText: TextView
    private lateinit var portInput: EditText
    private lateinit var applyPortButton: Button
    private lateinit var batteryOptimizationButton: Button
    private lateinit var batteryOptimizationText: TextView
    private val settings by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startServerService()
                refreshUi()
            } else {
                statusText.text = "Notification permission was denied. The server can still start, but foreground service behavior may be limited on newer Android versions."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        statusText = TextView(this).apply {
            textSize = 18f
            text = "Android TTS Server"
        }

        endpointText = TextView(this).apply {
            textSize = 16f
            text = "Endpoint: http://${NetworkUtils.getBestLocalIpAddress()}:${ServerRuntimeState.port}/"
            setTextIsSelectable(true)
        }

        portInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(settings.getInt(KEY_PORT, ServerRuntimeState.port).toString())
            hint = "Port"
        }

        applyPortButton = Button(this).apply {
            text = "Apply Port"
            setOnClickListener { applyPortFromUi() }
        }

        batteryOptimizationText = TextView(this).apply {
            textSize = 14f
        }

        batteryOptimizationButton = Button(this).apply {
            text = "Open Battery Optimization Settings"
            setOnClickListener { openBatteryOptimizationSettings() }
        }

        val helpText = TextView(this).apply {
            textSize = 14f
            text = buildString {
                appendLine("API Endpoints:")
                appendLine("GET / (Web UI)")
                appendLine("GET /api/voices")
                appendLine("POST /api/tts")
                appendLine("Port is configured from this screen.")
            }
            setTextIsSelectable(true)
        }

        container.addView(statusText)
        container.addView(endpointText)
        container.addView(portInput)
        container.addView(applyPortButton)
        container.addView(batteryOptimizationText)
        container.addView(batteryOptimizationButton)
        container.addView(helpText)
        root.addView(container)
        setContentView(root)

        ensureNotificationPermissionThenStart()
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        // Register receiver to get state updates from the service
        try {
            registerReceiver(stateReceiver, IntentFilter(TtsServerService.ACTION_STATE))
        } catch (_: Exception) {
        }
        refreshUi()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(stateReceiver)
        } catch (_: Exception) {
        }
    }

    private fun ensureNotificationPermissionThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        startServerService()
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            runOnUiThread { refreshUi() }
        }
    }

    private fun startServerService() {
        val intent = Intent(this, TtsServerService::class.java).apply {
            action = TtsServerService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopServerService() {
        val intent = Intent(this, TtsServerService::class.java).apply {
            action = TtsServerService.ACTION_STOP
        }
        startService(intent)
    }

    private fun refreshUi() {
        val state = ServerRuntimeState.snapshot()
        val ipAddress = NetworkUtils.getBestLocalIpAddress()
        endpointText.text = "Endpoint: http://$ipAddress:${state.port}/"
        if (portInput.text?.toString() != state.port.toString()) {
            portInput.setText(state.port.toString())
        }
        // Ensure UI reads the latest state; state may be updated from background thread
        if (state.running) {
            statusText.text = "Server running on port ${state.port}"
        } else if (state.errorMessage != null) {
            statusText.text = "Server error: ${state.errorMessage}"
        } else {
            statusText.text = "Server stopped"
        }

        updateBatteryOptimizationUi()
    }

    private fun applyPortFromUi() {
        val requestedPort = portInput.text?.toString()?.toIntOrNull()
        if (requestedPort == null || requestedPort !in 1..65535) {
            statusText.text = "Invalid port"
            return
        }
        settings.edit().putInt(KEY_PORT, requestedPort).apply()
        val intent = Intent(this, TtsServerService::class.java).apply {
            action = TtsServerService.ACTION_RESTART_PORT
            putExtra(TtsServerService.EXTRA_PORT, requestedPort)
        }
        ContextCompat.startForegroundService(this, intent)
        statusText.text = "Applying port $requestedPort"
    }

    private fun updateBatteryOptimizationUi() {
        val powerManager = getSystemService(PowerManager::class.java)
        val ignoring = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isIgnoringBatteryOptimizations(packageName)
        } else {
            true
        }
        batteryOptimizationText.text = if (ignoring) {
            "Battery optimization: exempt"
        } else {
            "Battery optimization: not exempt"
        }
        batteryOptimizationButton.isEnabled = !ignoring
    }

    private fun openBatteryOptimizationSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    companion object {
        private const val PREFS_NAME = "server_settings"
        private const val KEY_PORT = "server_port"
    }
}

object ServerRuntimeState {
    @Volatile var running: Boolean = false
    @Volatile var port: Int = 8080
    @Volatile var errorMessage: String? = null

    fun snapshot(): ServerRuntimeSnapshot = ServerRuntimeSnapshot(running, port, errorMessage)
}

data class ServerRuntimeSnapshot(
    val running: Boolean,
    val port: Int,
    val errorMessage: String?,
)
