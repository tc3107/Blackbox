package com.example.blackbox.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.blackbox.logging.AppLog as Log

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        val appContext = context.applicationContext
        runCatching {
            LocationEngineForegroundController.restoreAfterBoot(appContext)
        }.onFailure { throwable ->
            Log.e(
                BOOT_RESTORE_DEBUG_TAG,
                "Boot restore failed: ${throwable.message ?: "unknown error"}",
                throwable
            )
            LocationEngineForegroundController.markStopped(
                "Keepalive restore failed after boot: ${throwable.message ?: "unknown error"}"
            )
        }
    }

    private companion object {
        const val BOOT_RESTORE_DEBUG_TAG = "BlackboxBootRestore"
    }
}
