package net.re22.androidttsserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            val prefs = context.getSharedPreferences("server_settings", Context.MODE_PRIVATE)
            val port = prefs.getInt("server_port", 8080)
            if (port in 1..65535) {
                Log.i("BootReceiver", "Starting TtsServerService on boot (port: $port)")
                val serviceIntent = Intent(context, TtsServerService::class.java).apply {
                    action = TtsServerService.ACTION_START
                }
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                Log.i("BootReceiver", "Not starting TtsServerService on boot: invalid port $port")
            }
        }
    }
}
