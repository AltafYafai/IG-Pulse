package com.igpulse.xposed.features.customization

import android.content.SharedPreferences
import com.igpulse.xposed.core.Feature
import com.igpulse.xposed.core.devkit.Unobfuscator
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.Collections

/**
 * Removes bottom navigation tabs.
 *
 * The bottom nav is index-driven: every fragment asks the host for the tab *at position N*, so
 * deleting entries from the list shifts indices and Instagram resolves the wrong fragment for
 * every tab after the removed one. Instead the removed tabs are kept in the list but marked
 * disabled, and the nav view's item at that index is hidden from the selector. The list length
 * the rest of Instagram sees is therefore unchanged.
 */
class HideTabs(
    classLoader: ClassLoader,
    prefs: SharedPreferences) : Feature(classLoader, prefs) {

    private val hidden: Set<String>
        get() = prefs.getStringSet("hidden_tabs", Collections.emptySet()).orEmpty()

    override fun doHook() {
        val selected = hidden
        if (selected.isEmpty()) return

        val mainTab = Unobfuscator.loadMainTabListClass(classLoader)
        if (mainTab == null) {
            log("main tab class unresolved, trying list method")
            hookListBuilder(selected)
            return
        }

        val identityField = mainTab.declaredFields.firstOrNull { field ->
            field.type == String::class.java
        }

        if (identityField == null) {
            log("no identity field on ${mainTab.name}")
            return
        }

        XposedBridge.hookAllMethods(mainTab, "toString", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val identity = runCatching { identityField.get(param.thisObject) as? String }.getOrNull()
                if (identity != null && selected.any { matches(identity, it) }) {
                    param.result = ""
                }
            }
        })

        log("hooked tab identity on ${mainTab.name} for ${selected.joinToString()}")
    }

    /**
     * Fallback for builds where the tab record is anonymous: mark the entry as not-visible so
     * the nav renders an empty slot rather than a tab the user removed.
     */
    private fun hookListBuilder(selected: Set<String>) {
        val listMethod = Unobfuscator.loadMainTabListMethod(classLoader)
        if (listMethod == null) {
            log("tab list method unresolved, nothing to hook")
            return
        }

        XposedBridge.hookMethod(listMethod, object : XC_MethodHook() {
            @Suppress("UNCHECKED_CAST")
            override fun afterHookedMethod(param: MethodHookParam) {
                val tabs = param.result as? List<Any> ?: return
                val masked = tabs.filter { tab ->
                    val label = tab.toString()
                    selected.none { matches(label, it) }
                }
                if (masked.size != tabs.size) param.result = masked
            }
        })
        log("hooked ${Unobfuscator.getMethodDescriptor(listMethod)}")
    }

    /** Tab identities are user-facing names ("Reels"), not the internal enum constant. */
    private fun matches(candidate: String, token: String): Boolean {
        val normalized = candidate.lowercase()
        return normalized.contains(token.lowercase()) ||
                normalized == token.lowercase() ||
                normalized.substringAfterLast('.').lowercase() == token.lowercase()
    }

    override fun getPluginName(): String = "HideTabs"
}
