package com.example.lsdriver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class ChargingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_POWER_CONNECTED) {
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val enableCharging = prefs.getBoolean("enable_charging_popup", true)
            if (!enableCharging) return

            val serviceIntent = Intent(context, ChargingOverlayService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
