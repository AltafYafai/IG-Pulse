package com.igpulse.xposed.features.privacy

import android.content.SharedPreferences
import com.igpulse.xposed.core.Feature
import com.igpulse.xposed.core.devkit.Unobfuscator
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * Stops Instagram from reporting that a direct thread has been read.
 *
 * Instagram reports read state through the `direct_v2/seen_items` GraphQL mutation. The same
 * mutation is also used to *fetch* the other side's read state, so the hook inspects the
 * request's own thread-id argument and only suppresses the outbound direction - dropping
 * inbound data too would leave the blue ticks permanently wrong.
 */
class HideSeen(
    classLoader: ClassLoader,
    prefs: SharedPreferences) : Feature(classLoader, prefs) {

    private val enabled get() = prefs.getBoolean("hide_seen", false)

    /** Usernames to suppress for; empty means everyone. */
    private val allowlist: Set<String>
        get() = prefs.getString("hide_seen_thread_list", "")
            .orEmpty()
            .split(',', '\n', ' ')
            .map { it.trim().removePrefix("@").lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

    override fun doHook() {
        if (!enabled) return

        val method = Unobfuscator.loadSeenItemsMethod(classLoader)
            ?: Unobfuscator.loadSendSeenStateMethod(classLoader)
        if (method == null) {
            log("no seen-items method resolved, skipping")
            return
        }

        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val targets = allowlist
                if (targets.isEmpty() || matchesAnyParticipant(param, targets)) {
                    param.result = null
                    logDebug("suppressed seen report for ${param.thisObject}")
                }
            }
        })

        log("hooked ${Unobfuscator.getMethodDescriptor(method)}")
    }

    /**
     * Walks the request payload looking for an argument that matches one of [targets]. The
     * mutation is built from generic containers whose shape changes between releases, so this
     * searches the object graph rather than a fixed argument index.
     */
    private fun matchesAnyParticipant(param: XC_MethodHook.MethodHookParam, targets: Set<String>): Boolean {
        val seen = HashSet<Any>()
        val queue = ArrayDeque<Any?>()
        param.args.forEach { queue.add(it) }
        var inspected = 0

        while (queue.isNotEmpty() && inspected++ < MAX_GRAPH_NODES) {
            val node = queue.removeFirst() ?: continue
            if (!seen.add(node)) continue
            if (node is String) {
                val normalized = node.removePrefix("@").lowercase()
                if (normalized in targets) return true
                continue
            }
            if (node is CharSequence) {
                val normalized = node.toString().removePrefix("@").lowercase()
                if (normalized in targets) return true
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
            if (clazz.isArray) {
                val length = java.lang.reflect.Array.getLength(node)
                for (i in 0 until length) queue.add(java.lang.reflect.Array.get(node, i))
                continue
            }
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

    override fun getPluginName(): String = "HideSeen"

    private companion object {
        const val MAX_GRAPH_NODES = 400
    }
}
