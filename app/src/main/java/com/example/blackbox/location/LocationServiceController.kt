package com.example.blackbox.location

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

object LocationServiceController {
    val state = LocationServiceStateStore.state

    fun start(context: Context) {
        LocationServiceStateStore.markStarting()
        ContextCompat.startForegroundService(
            context,
            Intent(context, LocationForegroundService::class.java).apply {
                action = LocationForegroundService.ACTION_START
            }
        )
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, LocationForegroundService::class.java))
        LocationServiceStateStore.markStopped()
    }
}
