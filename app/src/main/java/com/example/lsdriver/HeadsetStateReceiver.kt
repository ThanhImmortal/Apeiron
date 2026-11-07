package com.example.lsdriver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

class HeadsetStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d("HeadsetStateReceiver", "Received action: $action")

        var isConnected = false

        when (action) {
            Intent.ACTION_HEADSET_PLUG -> {
                val state = intent.getIntExtra("state", -1)
                val name = intent.getStringExtra("name") ?: "Unknown"
                Log.d("HeadsetStateReceiver", "Headset state: $state, name: $name")
                if (state == 1) {
                    isConnected = true
                }
            }
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                Log.d("HeadsetStateReceiver", "USB device attached (Type-C headset)")
                isConnected = true
            }
        }

        if (isConnected) {
            val serviceIntent = Intent(context, AudioPopUp::class.java).apply {
                putExtra("media_type", "headset_plugged")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
