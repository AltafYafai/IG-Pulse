package com.igpulse.xposed.features.others

import android.content.SharedPreferences
import com.igpulse.IGPulse
import com.igpulse.xposed.core.Feature
import com.igpulse.xposed.core.FeatureLoader
import com.igpulse.xposed.core.IgCore
import com.igpulse.xposed.core.devkit.Unobfuscator
import com.igpulse.xposed.utils.Utils
import de.robv.android.xposed.XposedBridge
import java.io.File

/**
 * Diagnostics hook. Always loads, regardless of preferences, because it is what you reach for
 * when a feature silently fails to attach.
 *
 * Install LSPosed, run this module once, then use the broadcast below to probe a symbol against
 * the exact Instagram build installed on the device:
 *
 *   adb shell am broadcast -a com.igpulse.PROBE --es needle "direct_v2/seen_items"
 *
 * The response lands in the LSPosed log, which is the fastest way to re-pin an anchor after an
 * Instagram update without building a release.
 */
class DebugFeature(
    classLoader: ClassLoader,
    prefs: SharedPreferences) : Feature(classLoader, prefs) {

    override fun doHook() {
        installProbeReceiver()
        dumpEnvironment()
    }

    private fun installProbeReceiver() {
        val app = FeatureLoader.mApp ?: return
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                val needle = intent.getStringExtra("needle") ?: return
                val report = Unobfuscator.probe(classLoader, needle)
                XposedBridge.log(report)
                File(context.cacheDir, "last_probe.txt").writeText(report)
                Utils.showToast("Probe finished, see LSPosed log", android.widget.Toast.LENGTH_SHORT)
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            app, receiver,
            android.content.IntentFilter("com.igpulse.PROBE"),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun dumpEnvironment() {
        val app = FeatureLoader.mApp ?: return
        val version = app.packageManager.getPackageInfo(app.packageName, 0)
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        val density = app.resources.displayMetrics.density

        log("Instagram ${version.versionName} (${version.longVersionCode})")
        log("Android ${android.os.Build.VERSION.RELEASE} API ${android.os.Build.VERSION.SDK_INT}")
        log("Module ${com.igpulse.BuildConfig.VERSION_NAME} abi=$abi density=$density")
        log("resources injected: ${IGPulse.resParam != null}")
        log("host: ${IgCore.moduleContext.packageName}")

        // TEMP-DIAG(449): dexkit blindness check, revert after re-pinning anchors.
        runCatching {
            val appInfo = app.applicationInfo
            val apkSize = runCatching { File(appInfo.sourceDir).length() }.getOrDefault(-1)
            log("apk: ${appInfo.sourceDir} size=$apkSize splits=${appInfo.splitSourceDirs?.size ?: 0}")
            listOf("instagram", "MainTab", "direct_v2/").forEach { needle ->
                val report = runCatching { Unobfuscator.probe(classLoader, needle) }
                    .getOrElse { "probe FAILED: ${it.message}" }
                report.lineSequence().take(6).forEach { log(it) }
            }
        }.onFailure { log("diag failed: ${it.message}") }

        listOf(
            "getPresence" to Unobfuscator.loadGetPresenceMethod(classLoader),
            "seenItems" to Unobfuscator.loadSeenItemsMethod(classLoader),
            "activityIndicator" to Unobfuscator.loadActivityIndicatorMethod(classLoader),
            "storySeen" to Unobfuscator.loadStorySeenMethod(classLoader),
            "mediaUrlFactory" to Unobfuscator.loadMediaUrlFactoryClass(classLoader),
            "imageFactory" to Unobfuscator.loadImageFactoryClass(classLoader),
            "mediaViewerFragment" to Unobfuscator.loadMediaViewerFragmentClass(classLoader),
            "reelViewerFragment" to Unobfuscator.loadReelViewerFragmentClass(classLoader),
            "reelsTabFragment" to Unobfuscator.loadReelsTabFragmentClass(classLoader),
            "mainTabListClass" to Unobfuscator.loadMainTabListClass(classLoader),
            "mainTabListMethod" to Unobfuscator.loadMainTabListMethod(classLoader)
        ).forEach { (name, resolved) ->
            logDebug(
                if (resolved is java.lang.reflect.Method) Unobfuscator.getMethodDescriptor(resolved)
                else (resolved as Class<*>?)?.name ?: "UNRESOLVED"
            )
        }

        if (FeatureLoader.errors().isNotEmpty()) {
            log("pending hook errors: ${FeatureLoader.errors().size}")
            FeatureLoader.errors().forEach { XposedBridge.log(it) }
        }
    }

    override fun getPluginName(): String = "DebugFeature"
}
