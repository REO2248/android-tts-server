package net.re22.androidttsserver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
class TtsServerService : Service() {
    private var ttsEngineManager: TtsEngineManager? = null
    private var httpServer: SimpleHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val starting = java.util.concurrent.atomic.AtomicBoolean(false)
    private val restartLock = Any()
    private val TAG = "TtsServerService"

    override fun onCreate() {
        super.onCreate()
        ServerRuntimeState.port = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(KEY_PORT, ServerRuntimeState.port)
        startInForeground()
        // Start server in background thread to avoid blocking
        Thread { ensureServerRunning() }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART_PORT -> {
                val requestedPort = intent.getIntExtra(EXTRA_PORT, -1)
                if (requestedPort in 1..65535) {
                    updatePortPreference(requestedPort)
                    restartServer(requestedPort)
                }
                return START_STICKY
            }
            else -> {
                if (!ServerRuntimeState.running) {
                    ensureServerRunning()
                }
                return START_STICKY
            }
        }
    }

    private fun ensureServerRunning() {
        if (!starting.compareAndSet(false, true)) {
            Log.i(TAG, "ensureServerRunning: already starting/running")
            return
        }
        try {
            val port = ServerRuntimeState.port
            Log.i(TAG, "Starting TTS engine and HTTP server on port $port")
            ttsEngineManager = TtsEngineManager(this)
            httpServer = SimpleHttpServer(port, requireNotNull(ttsEngineManager)) { newPort ->
                // Port change requested from UI
                restartServer(newPort)
            }
            httpServer?.start()
            // Update state synchronously to ensure UI reflects immediately
            synchronized(ServerRuntimeState) {
                ServerRuntimeState.running = true
                // port is already set
                ServerRuntimeState.errorMessage = null
            }
            updateNotification("Listening on port $port")
            // Broadcast state change so UI can refresh immediately
            broadcastState()
        } catch (error: Exception) {
            Log.e(TAG, "Failed to start server", error)
            synchronized(ServerRuntimeState) {
                ServerRuntimeState.running = false
                ServerRuntimeState.errorMessage = error.message
            }
            updateNotification("Error: ${error.message.orEmpty()}")
            broadcastState()
            starting.set(false)
        }
    }

    private fun restartServer(newPort: Int) {
        synchronized(restartLock) {
            try {
                Log.i(TAG, "Restarting server on port $newPort")
                // Stop current server
                httpServer?.stop()
                httpServer = null
                // Ensure TTS engine is initialized
                if (ttsEngineManager == null) ttsEngineManager = TtsEngineManager(this)
                // Update port state
                synchronized(ServerRuntimeState) {
                    ServerRuntimeState.port = newPort
                }
                // Start new server
                httpServer = SimpleHttpServer(newPort, requireNotNull(ttsEngineManager)) { port -> restartServer(port) }
                httpServer?.start()
                synchronized(ServerRuntimeState) {
                    ServerRuntimeState.running = true
                    ServerRuntimeState.errorMessage = null
                }
                updateNotification("Listening on port $newPort")
                broadcastState()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart server", e)
                synchronized(ServerRuntimeState) {
                    ServerRuntimeState.running = false
                    ServerRuntimeState.errorMessage = e.message
                }
                updateNotification("Error: ${e.message.orEmpty()}")
                broadcastState()
                starting.set(false)
            }
        }
    }

    private fun updatePortPreference(port: Int) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putInt(KEY_PORT, port)
            .apply()
    }

    private fun broadcastState() {
        val intent = Intent(ACTION_STATE).apply {
            putExtra("running", ServerRuntimeState.running)
            putExtra("port", ServerRuntimeState.port)
            putExtra("error", ServerRuntimeState.errorMessage)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        httpServer?.stop()
        ttsEngineManager?.shutdown()
        httpServer = null
        ttsEngineManager = null
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
            }
        }
        wakeLock = null
        starting.set(false)
        ServerRuntimeState.running = false
        // Broadcast stopped state so UI shows correct status
        broadcastState()
        updateNotification("Stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        createNotificationChannel()
        acquireWakeLock()
        val port = ServerRuntimeState.port
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("🎤 TTS Server Running")
            .setContentText("http://localhost:$port - Listening for requests")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$TAG:server",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("🎤 TTS Server Running")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "TTS Server",
                NotificationManager.IMPORTANCE_MIN,
            )
            channel.enableVibration(false)
            channel.enableLights(false)
            channel.setSound(null, null)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_START = "net.re22.androidttsserver.action.START"
        const val ACTION_STOP = "net.re22.androidttsserver.action.STOP"
        const val ACTION_RESTART_PORT = "net.re22.androidttsserver.action.RESTART_PORT"
        const val ACTION_STATE = "net.re22.androidttsserver.action.STATE"
        const val EXTRA_PORT = "port"
        private const val NOTIFICATION_CHANNEL_ID = "tts_server"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS_NAME = "server_settings"
        private const val KEY_PORT = "server_port"
    }
}
