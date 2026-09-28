package com.igpulse.xposed.features.customization

import android.content.SharedPreferences
import com.igpulse.xposed.core.Feature
import com.igpulse.xposed.utils.DesignUtils
import com.igpulse.xposed.utils.Utils
import de.robv.android.xposed.XposedBridge

/**
 * Overrides Instagram's accent and surface colours.
 *
 * Instagram reads its palette through the resource table rather than hard-coded constants, so
 * swapping the entries in `XResources` is enough: every `getColor` for that resource name in
 * the Instagram process returns the module's value, and Instagram's own light/dark attribute
 * plumbing keeps working because only the resolved colour changes.
 */
class CustomTheme(
    classLoader: ClassLoader,
    prefs: SharedPreferences) : Feature(classLoader, prefs) {

    private val enabled get() = prefs.getBoolean("changecolor", false)

    override fun doHook() {
        if (!enabled) return

        val palette = DesignUtils.palette(prefs) ?: run {
            log("no colour resolved, skipping")
            return
        }

        val night = DesignUtils.isNightMode()
        val mapped = listOf(
            "instagram_color_primary" to palette.primary,
            "ig_color_accent" to palette.primary,
            "colorPrimary" to palette.primary,
            "instagram_background" to palette.background,
            "colorBackground" to palette.background,
            "colorSurface" to palette.background,
            "text_color" to palette.text,
            "colorOnBackground" to palette.text,
            "colorOnSurface" to palette.text
        )

        var patched = 0
        for ((name, color) in mapped) {
            if (Utils.getID(name, "color") <= 0) continue
            if (DesignUtils.setReplacementColor(name, color, night)) patched++
        }

        if (patched == 0) {
            log("no known colour resource matched; Instagram's palette names likely changed")
        } else {
            log("overrode $patched colour resources (night=$night)")
        }

        XposedBridge.log("[IG-Pulse] theme active: ${palette.describe()}")
    }

    override fun getPluginName(): String = "CustomTheme"
}
