package com.igpulse.xposed.features.privacy

import android.content.SharedPreferences
import com.igpulse.xposed.core.Feature
import com.igpulse.xposed.core.devkit.Unobfuscator
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * Selectively hides the viewer's story views.
 *
 * Story views are recorded server-side by `direct_v2/reels_media_seen`, so the filter has to
 * be applied on the *outbound* seen mutation. Entries in the list are usernames; an empty list
 * means "hide from everyone".
 *
 * Instagram has no server-side way to retract a view that was already sent, so a view that has
 * gone out stays gone until the story expires. The preference is therefore evaluated at send
 * time, which is the only point where it can still change the outcome.
 */
class HideStoryView(
    classLoader: ClassLoader,
    prefs: SharedPreferences) : Feature(classLoader, prefs) {

    private val enabled get() = prefs.getBoolean("hide_story_views", false)

    private val blocked: Set<String>
        get() = prefs.getString("hide_story_views_list", "")
            .orEmpty()
            .split(',', '\n', ' ')
            .map { it.trim().removePrefix("@").lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

    override fun doHook() {
        if (!enabled) return

        val method = Unobfuscator.loadStorySeenMethod(classLoader)
        if (method == null) {
            log("story seen mutation unresolved, skipping")
            return
        }

        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val targets = blocked
                if (targets.isEmpty() || targets.any { mentions(param, it) }) {
                    param.result = null
                    logDebug("suppressed story view report")
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

    override fun getPluginName(): String = "HideStoryView"

    private companion object {
        const val MAX_GRAPH_NODES = 300
    }
}
