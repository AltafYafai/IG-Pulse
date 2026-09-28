package com.igpulse.xposed.bridge.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

class BridgeService : Service() {
    override fun onBind(intent: Intent?): IBinder {
        return HookBinder
    }
}
