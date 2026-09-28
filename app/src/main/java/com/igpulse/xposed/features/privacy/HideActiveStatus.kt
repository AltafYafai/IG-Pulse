package com.igpulse.xposed.features.privacy

import android.content.SharedPreferences
import com.igpulse.xposed.core.Feature
import com.igpulse.xposed.core.devkit.Unobfuscator
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * Stops Instagram from reporting the viewer's presence in Direct.
 *
 * The `direct_v2/get_presence` request is also how the app *reads* the other side's online
 * state, so blanket-suppressing it would leave the whole presence list empty. The hook
 * therefore inspects the request for the viewer's own id and only drops the mutation that
 * publishes it.
 */
class HideActiveStatus(
    classLoader: ClassLoader,
    prefs: SharedPreferences) : Feature(classLoader, prefs) {

    private val enabled get() = prefs.getBoolean("hide_active_status", false)

    override fun doHook() {
        if (!enabled) return

        val method = Unobfuscator.loadGetPresenceMethod(classLoader)
        if (method == null) {
            log("get_presence unresolved, skipping")
            return
        }

        val viewer = com.igpulse.xposed.utils.Utils.getMyUsername()
        logDebug("viewer username resolved as '$viewer'")

        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (viewer.isNotEmpty() && mentions(param, viewer)) {
                    param.result = null
                    logDebug("suppressed presence publish for $viewer")
                }
            }
        })

        log("hooked ${Unobfuscator.getMethodDescriptor(method)}")
    }

    private fun mentions(param: XC_MethodHook.MethodHookParam, needle: String): Boolean {
        val lowered = needle.lowercase()
        val seen = HashSet<Any>()
        val queue = ArrayDeque<Any?>()
        param.args.forEach { queue.add(it) }
        var inspected = 0

        while (queue.isNotEmpty() && inspected++ < MAX_GRAPH_NODES) {
            val node = queue.removeFirst() ?: continue
            if (!seen.add(node)) continue
            if (node is CharSequence) {
                if (node.toString().lowercase().contains(lowered)) return true
                continue
            }
            if (node is Number || node is Boolean) continue
            if (node is Iterable<*>) {
                node.forEach { queue.add(it) }
                continue
            }
            if (node is Map<*, *>) {
                node.values.forEach { queue.add(it) }
                continue
            }
            val clazz = node.javaClass
            if (clazz.name.startsWith("android.") || clazz.name.startsWith("java.")) continue
            for (field in clazz.declaredFields) {
                if (field.type.isPrimitive) continue
                runCatching {
                    field.isAccessible = true
                    queue.add(field.get(node))
                }
            }
        }
        return false
    }

    override fun getPluginName(): String = "HideActiveStatus"

    private companion object {
        const val MAX_GRAPH_NODES = 300
    }
}
