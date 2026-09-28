package com.igpulse.xposed.features.privacy

import android.content.SharedPreferences
import com.igpulse.xposed.core.Feature
import com.igpulse.xposed.core.devkit.Unobfuscator
import com.igpulse.xposed.utils.Utils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.io.File

/**
 * Notifies the viewer when someone captures their screen inside Direct.
 *
 * Instagram only reports screenshots from its own UI, and not at all for the OS-level screen
 * recorder, so this hooks the platform callbacks instead: `View.setDrawListener`-adjacent
 * screenshot detection is not exposed to apps, but `WindowManager` view hierarchy capture and
 * the `onScreenCaptured` path for Direct's own viewer are. The most reliable cross-version
 * signal available to a module is the Direct media viewer's screenshot/record dialog, which
 * Instagram shows after the fact.
 */
class ScreenshotNotify(
    classLoader: ClassLoader,
    prefs: SharedPreferences) : Feature(classLoader, prefs) {

    private val enabled get() = prefs.getBoolean("screenshot_notify", false)

    override fun doHook() {
        if (!enabled) return

        val viewer = Unobfuscator.loadMediaViewerFragmentClass(classLoader)
        val storyViewer = Unobfuscator.loadReelViewerFragmentClass(classLoader)

        val targets = listOfNotNull(viewer, storyViewer)
        if (targets.isEmpty()) {
            log("no viewer fragment resolved, cannot detect capture")
            return
        }

        targets.forEach { fragment ->
            val detector = fragment.declaredMethods.firstOrNull { method ->
                method.parameterCount == 0 &&
                        method.name.contains("creenshot", ignoreCase = true) &&
                        method.returnType == Void.TYPE
            } ?: run {
                log("no screenshot callback on ${fragment.name}")
                return@forEach
            }

            XposedBridge.hookMethod(detector, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    notifyOnce()
                }
            })
            log("hooked ${Unobfuscator.getMethodDescriptor(detector)}")
        }
    }

    private fun notifyOnce() {
        val marker = File(Utils.application.cacheDir, "last_capture_notice")
        if (System.currentTimeMillis() - marker.lastModified() < DEBOUNCE_MS) return
        runCatching { marker.createNewFile() }

        Utils.showNotification(
            Utils.getString(com.igpulse.R.string.screenshot_notify),
            Utils.getString(com.igpulse.R.string.screenshot_notify_summary)
        )
    }

    override fun getPluginName(): String = "ScreenshotNotify"

    private companion object {
        const val DEBOUNCE_MS = 3_000L
    }
}
