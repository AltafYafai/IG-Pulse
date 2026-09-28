package com.igpulse.xposed.bridge.client

import com.igpulse.xposed.bridge.IgIIFace

abstract class BaseClient {
    abstract val service: IgIIFace?

    abstract suspend fun connect(): Boolean

    abstract fun tryReconnect()
}
