package com.igpulse.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.igpulse.xposed.bridge.service.BridgeService

/**
 * Invisible trampoline used to satisfy Android 11+ background activity launch restrictions.
 *
 * The Instagram process needs the module app to open a file for it, but it cannot start an
 * activity from the background. Launching this translucent activity through the hooked
 * `SettingsProvider` call puts the module app in the foreground for the duration of the bind,
 * after which the AIDL binder stays available even once this activity finishes.
 */
class ForceStartActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent(this, BridgeService::class.java)
        runCatching {
            startForegroundService(intent)
        }.onFailure {
            runCatching { startService(intent) }
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
