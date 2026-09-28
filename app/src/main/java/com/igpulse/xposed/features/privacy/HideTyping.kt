package com.igpulse.xposed.features.privacy

import android.content.SharedPreferences
import com.igpulse.xposed.core.Feature
import com.igpulse.xposed.core.devkit.Unobfuscator
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * Suppresses the "typing…" indicator on outgoing direct messages.
 *
 * Two layers, because Instagram emits the indicator from two places on some builds:
 *  1. The `direct_v2/activity_indicator` transport is short-circuited outright.
 *  2. As a fallback, the optimistic local echo is blocked at the presence-cache setter, which
 *     is what the UI reads to render the label immediately.
 */
class HideTyping(
    classLoader: ClassLoader,
    prefs: SharedPreferences) : Feature(classLoader, prefs) {

    private val enabled get() = prefs.getBoolean("hide_typing", false)

    override fun doHook() {
        if (!enabled) return

        val transport = Unobfuscator.loadActivityIndicatorMethod(classLoader)
        if (transport == null) {
            log("activity-indicator transport unresolved, trying local presence cache")
        } else {
            XposedBridge.hookMethod(transport, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = null
                }
            })
            log("hooked transport ${Unobfuscator.getMethodDescriptor(transport)}")
        }

        hookLocalPresenceCache()
    }

    private fun hookLocalPresenceCache() {
        val indicatorClass = Unobfuscator.loadActivityIndicatorClass(classLoader)
        if (indicatorClass == null) {
            log("no local presence indicator class, transport hook only")
            return
        }

        val setter = indicatorClass.declaredMethods.firstOrNull { method ->
            method.parameterCount == 1 &&
                    (method.parameterTypes[0] == Long::class.javaPrimitiveType ||
                            method.parameterTypes[0] == java.lang.Long::class.java) &&
                    method.returnType == Void.TYPE
        }

        if (setter == null) {
            log("no presence cache setter on ${indicatorClass.name}")
            return
        }

        XposedBridge.hookMethod(setter, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.args.firstOrNull() !is Long) return
                param.result = null
            }
        })
        log("hooked presence cache ${Unobfuscator.getMethodDescriptor(setter)}")
    }

    override fun getPluginName(): String = "HideTyping"
}
