package com.igpulse.xposed.core

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.crossbowffs.remotepreferences.RemotePreferences
import com.igpulse.BuildConfig
import com.igpulse.R
import com.igpulse.IGPulse
import com.igpulse.activities.CrashReportActivity
import com.igpulse.xposed.core.devkit.Unobfuscator
import com.igpulse.xposed.core.devkit.UnobfuscatorCache
import com.igpulse.xposed.features.customization.CustomTheme
import com.igpulse.xposed.features.customization.GridColumns
import com.igpulse.xposed.features.customization.HideTabs
import com.igpulse.xposed.features.media.MediaDownloader
import com.igpulse.xposed.features.others.DebugFeature
import com.igpulse.xposed.features.utility.ContentActions
import com.igpulse.xposed.features.privacy.HideActiveStatus
import com.igpulse.xposed.features.privacy.HideSeen
import com.igpulse.xposed.features.privacy.HideStoryView
import com.igpulse.xposed.features.privacy.HideTyping
import com.igpulse.xposed.features.privacy.ScreenshotNotify
import com.igpulse.xposed.utils.DesignUtils
import com.igpulse.xposed.utils.ReflectionUtils
import com.igpulse.xposed.utils.Utils
import de.robv.android.xposed.SELinuxHelper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.Calendar
import java.util.Collections
import java.util.Date
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FeatureLoader {

    companion object {
        @JvmField
        var mApp: Application? = null

        const val PACKAGE_IG = "com.instagram.android"

        private val errors = Collections.synchronizedList(ArrayList<ErrorItem>())
        private var supportedVersions: List<String>? = null
        private var currentVersion: String? = null
        private var crashHandlerInstalled = false
        private const val UPDATE_CHECK_COOLDOWN_MS = 6 * 60 * 60 * 1000L
        private var lastUpdateCheckScheduledAt = 0L

        @JvmStatic
        fun start(loader: ClassLoader, sourceDir: String) {
            if (!Unobfuscator.initWithPath(sourceDir)) {
                XposedBridge.log("[IG-Pulse] Can't init dexkit, aborting")
                return
            }

            Utils.appClassLoader = loader

            XposedHelpers.findAndHookMethod(
                Instrumentation::class.java, "callApplicationOnCreate", Application::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        mApp = param.args[0] as Application
                        val app = mApp!!
                        val pref = getPreferences(app)
                        Feature.DEBUG = pref.getBoolean("enablelogs", true)
                        Utils.xprefs = pref

                        val packageInfo = app.packageManager.getPackageInfo(app.packageName, 0)
                        currentVersion = packageInfo.versionName
                        XposedBridge.log("[IG-Pulse] Instagram ${packageInfo.versionName}")
                        installCrashHandler(app, packageInfo.versionName.orEmpty())

                        supportedVersions = resolveSupportedVersions(app, pref)

                        app.registerActivityLifecycleCallbacks(IgActivityCallbacks())
                        registerReceivers(app)

                        try {
                            IgCore.initialize(app)
                            IgCore.initModuleContext(app)
                            UnobfuscatorCache.init(app)
                            ReflectionUtils.initCache(app)
                            DesignUtils.setPrefs(pref)
                            Utils.init()

                            if (!isVersionSupported(packageInfo.versionName, supportedVersions)) {
                                val msg = "Unsupported Instagram version: ${packageInfo.versionName}. " +
                                        "Only the expiration bypass was applied. " +
                                        "Supported: ${supportedVersions?.joinToString()}"
                                if (pref.getBoolean("bypass_version_check", false)) {
                                    XposedBridge.log("[IG-Pulse] $msg (bypassed)")
                                } else {
                                    throw Exception(msg)
                                }
                            }

                            disableExpirationVersion(app.classLoader)
                            loadFeatures(loader, pref, packageInfo.versionName!!)
                            scheduleUpdateCheck(pref)
                            sendEnabledBroadcast(app)

                            XposedBridge.log("[IG-Pulse] Hooks installed")
                        } catch (e: Throwable) {
                            XposedBridge.log(e)
                            errors.add(
                                ErrorItem(
                                    pluginName = "MainFeatures[Critical]",
                                    igVersion = packageInfo.versionName,
                                    moduleVersion = BuildConfig.VERSION_NAME,
                                    message = e.message,
                                    errorDetail = e.stackTraceToString()
                                )
                            )
                        }
                    }
                }
            )

            // Surface collected hook failures inside Instagram once its UI is up.
            XposedHelpers.findAndHookMethod(
                Activity::class.java, "onCreate", Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.thisObject.javaClass.simpleName != "MainActivity") return
                        val snapshot = synchronized(errors) { errors.toList() }
                        if (snapshot.isEmpty()) return
                        val activity = param.thisObject as Activity
                        IgCore.showAlert(
                            activity,
                            title = activity.getString(R.string.error_detected),
                            message = buildString {
                                append(activity.getString(R.string.version_error))
                                append(snapshot.joinToString("\n") { "${it.pluginName} - ${it.message}" })
                                append("\n\nInstagram: $currentVersion")
                                append("\nSupported:\n${supportedVersions?.joinToString("\n")}")
                            },
                            positive = activity.getString(R.string.ok),
                            copyToClipboard = true
                        )
                    }
                }
            )
        }

        /**
         * Instagram has no WhatsApp-style "expiration" gate in the same code path, but it does
         * refuse to run on builds it considers stale. Neutralising every `Date`-returning method
         * on the matched class is the cheapest reliable way to keep betas installable.
         */
        @JvmStatic
        @Throws(Exception::class)
        fun disableExpirationVersion(classLoader: ClassLoader) {
            val expirationClass = Unobfuscator.loadExpirationClass(classLoader) ?: return
            val methods = ReflectionUtils.findAllMethodsUsingFilter(expirationClass) {
                m -> m.returnType == Date::class.java
            }
            for (method in methods) {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = Calendar.getInstance().apply {
                            set(2099, 11, 31)
                        }.time
                    }
                })
            }
        }

        private fun getPreferences(context: Context): SharedPreferences {
            val pref = IGPulse.getPref()
            pref.reload()
            try {
                val readable = SELinuxHelper.getAppDataFileService()
                    .checkFileAccess(pref.file.absolutePath, 4)
                if (readable) return pref
            } catch (e: Exception) {
                XposedBridge.log(e)
            }
            XposedBridge.log("[IG-Pulse] XSharedPreferences unreadable, RemotePreferences fallback")
            return RemotePreferences(
                context,
                BuildConfig.APPLICATION_ID + ".preferences",
                BuildConfig.APPLICATION_ID + "_preferences"
            )
        }

        private fun registerReceivers(app: Application) {
            val restartReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (context.packageName == intent.getStringExtra("PKG")) {
                        if (!Utils.doRestart(context)) {
                            Toast.makeText(context, R.string.rebooting, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            ContextCompat.registerReceiver(
                app, restartReceiver,
                IntentFilter("${BuildConfig.APPLICATION_ID}.INSTAGRAM.RESTART"),
                ContextCompat.RECEIVER_EXPORTED
            )

            val checkReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    sendEnabledBroadcast(context)
                }
            }
            ContextCompat.registerReceiver(
                app, checkReceiver,
                IntentFilter("${BuildConfig.APPLICATION_ID}.CHECK_IG"),
                ContextCompat.RECEIVER_EXPORTED
            )
        }

        private fun sendEnabledBroadcast(context: Context) {
            try {
                val intent = Intent("${BuildConfig.APPLICATION_ID}.RECEIVER_IG").apply {
                    putExtra("VERSION", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
                    putExtra("PKG", context.packageName)
                    setPackage(BuildConfig.APPLICATION_ID)
                }
                context.sendBroadcast(intent)
            } catch (_: Exception) {
            }
        }

        private fun loadFeatures(loader: ClassLoader, pref: SharedPreferences, version: String) {
            val classes = arrayOf(
                // diagnostics first: it reports how the rest of the load went
                DebugFeature::class.java,
                // privacy
                HideSeen::class.java,
                HideTyping::class.java,
                HideActiveStatus::class.java,
                HideStoryView::class.java,
                ScreenshotNotify::class.java,
                // media
                MediaDownloader::class.java,
                // customization
                HideTabs::class.java,
                CustomTheme::class.java,
                GridColumns::class.java,
                // utilities
                ContentActions::class.java
            )

            XposedBridge.log("[IG-Pulse] Loading ${classes.size} features")
            val executor = Executors.newSingleThreadExecutor { r ->
                Thread(r, "IGP-HookInstaller").apply { isDaemon = true }
            }
            val timings = Collections.synchronizedList(ArrayList<String>())

            for (clazz in classes) {
                CompletableFuture.runAsync({
                    val started = System.currentTimeMillis()
                    try {
                        val ctor = clazz.getConstructor(
                            ClassLoader::class.java,
                            SharedPreferences::class.java
                        )
                        (ctor.newInstance(loader, pref) as Feature).doHook()
                    } catch (e: Throwable) {
                        XposedBridge.log(e)
                        errors.add(
                            ErrorItem(
                                pluginName = clazz.simpleName,
                                igVersion = version,
                                moduleVersion = BuildConfig.VERSION_NAME,
                                message = e.message,
                                errorDetail = e.stackTraceToString()
                            )
                        )
                    }
                    timings.add("* ${clazz.simpleName} in ${System.currentTimeMillis() - started}ms")
                }, executor)
            }

            executor.shutdown()
            executor.awaitTermination(20, TimeUnit.SECONDS)
            if (Feature.DEBUG) {
                synchronized(timings) { timings.toList() }.forEach { XposedBridge.log(it) }
            }
        }

        /**
         * Update checks run from inside Instagram because that is the only process guaranteed to
         * be alive when a new module build ships. Rate-limited so it costs one request per six
         * hours at most.
         */
        private fun scheduleUpdateCheck(pref: SharedPreferences) {
            if (!pref.getBoolean("update_check", true)) return
            ActivityStateRegistry.addListener { activity, type ->
                if (activity.javaClass.simpleName != "MainActivity") return@addListener
                if (type != ActivityStateRegistry.ChangeType.RESUMED) return@addListener
                val now = System.currentTimeMillis()
                val due = synchronized(FeatureLoader::class.java) {
                    if (now - lastUpdateCheckScheduledAt < UPDATE_CHECK_COOLDOWN_MS) {
                        false
                    } else {
                        lastUpdateCheckScheduledAt = now
                        true
                    }
                }
                if (!due) return@addListener
                activity.window.decorView.postDelayed({
                    CompletableFuture.runAsync(com.igpulse.UpdateChecker(activity))
                }, 2_000)
            }
        }

        private fun resolveSupportedVersions(
            app: Application,
            pref: SharedPreferences
        ): List<String> {
            val local = runCatching {
                app.resources.getStringArray(R.array.supported_versions).toList()
            }.getOrDefault(emptyList())
            val remote = getRemoteSupportedVersions(pref)
            val merged = if (remote.isNullOrEmpty()) local else (local + remote).distinct()
            XposedBridge.log(
                "[IG-Pulse] Supported versions: $merged (local=${local.size}, remote=${remote?.size ?: 0})"
            )
            return merged
        }

        private fun installCrashHandler(application: Application, igVersion: String) {
            if (crashHandlerInstalled) return
            crashHandlerInstalled = true
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    XposedBridge.log(throwable)
                    val mainThread = Looper.getMainLooper().thread == thread
                    if (!mainThread && throwable !is Error) {
                        previous?.uncaughtException(thread, throwable)
                        return@setDefaultUncaughtExceptionHandler
                    }
                    val intent = Intent().apply {
                        component = android.content.ComponentName(
                            BuildConfig.APPLICATION_ID,
                            CrashReportActivity::class.java.name
                        )
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        putExtra(CrashReportActivity.EXTRA_CRASH_INFO, crashInfo(application, igVersion))
                        putExtra(CrashReportActivity.EXTRA_CRASH_TRACE, Log.getStackTraceString(throwable))
                    }
                    application.startActivity(intent)
                } catch (e: Throwable) {
                    XposedBridge.log(e)
                } finally {
                    if (previous != null) previous.uncaughtException(thread, throwable)
                    else Runtime.getRuntime().exit(2)
                }
            }
        }

        private fun crashInfo(application: Application, igVersion: String): String = listOf(
            "${application.getString(R.string.instagram_version)}: $igVersion",
            "${application.getString(R.string.instagram_package)}: ${application.packageName}",
            "${application.getString(R.string.igpulse_version)}: ${BuildConfig.VERSION_NAME}",
            "${application.getString(R.string.crash_android_version)}: " +
                    "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "${application.getString(R.string.device_model)}: ${Build.MANUFACTURER} ${Build.MODEL}"
        ).joinToString("\n")

        @JvmStatic
        fun errors(): List<String> = synchronized(errors) { errors.toList() }.map { it.toString() }

        @JvmStatic
        fun isVersionSupported(version: String?, supported: List<String>?): Boolean {
            if (version == null || supported.isNullOrEmpty()) return false
            if (supported.any { version.startsWith(it.replace(".xx", "")) }) return true
            return isFutureBetaVersion(version)
        }

        /**
         * Instagram ships stable and beta from the same version line, so a hard block on the
         * allowlist bricks every user on a new build. Instead anything at or above the baseline
         * is loaded optimistically and per-feature failures are reported individually.
         */
        @JvmStatic
        fun isFutureBetaVersion(version: String?): Boolean {
            if (version.isNullOrBlank()) return false
            val match = Regex("""^(\d+)\.(\d+)\.(\d+)""").find(version.trim()) ?: return false
            val parts = (1..3).map { match.groupValues[it].toInt() }
            val (major, minor, patch) = Triple(parts[0], parts[1], parts[2])
            val baseline = BASELINE
            return when {
                major > baseline.first -> true
                major < baseline.first -> false
                minor > baseline.second -> true
                minor < baseline.second -> false
                else -> patch >= baseline.third
            }
        }

        private val BASELINE = Triple(373, 0, 0) // first Instagram version this module shipped against

        private fun getRemoteSupportedVersions(pref: SharedPreferences): List<String>? {
            try {
                val raw = IgCore.getPrivString("remote_supported_versions", null)
                if (!raw.isNullOrBlank()) {
                    return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                }
            } catch (_: Exception) {
            }
            return runCatching {
                pref.getString("remote_supported_versions", null)
                    ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            }.getOrNull()
        }
    }

    private class ErrorItem(
        val pluginName: String? = null,
        val igVersion: String? = null,
        val moduleVersion: String? = null,
        val message: String? = null,
        val errorDetail: String? = null
    ) {
        override fun toString(): String =
            "pluginName='$pluginName'\nmoduleVersion='$moduleVersion'\n" +
                    "instagramVersion='$igVersion'\nmessage=$message\nerror='$errorDetail'"
    }

    /** Feeds [ActivityStateRegistry] so features can reach a live Instagram window. */
    private class IgActivityCallbacks : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(a: Activity, b: Bundle?) =
            ActivityStateRegistry.updateState(a, ActivityStateRegistry.ChangeType.CREATED)

        override fun onActivityStarted(a: Activity) =
            ActivityStateRegistry.updateState(a, ActivityStateRegistry.ChangeType.STARTED)

        override fun onActivityResumed(a: Activity) =
            ActivityStateRegistry.updateState(a, ActivityStateRegistry.ChangeType.RESUMED)

        override fun onActivityPaused(a: Activity) =
            ActivityStateRegistry.updateState(a, ActivityStateRegistry.ChangeType.PAUSED)

        override fun onActivityStopped(a: Activity) =
            ActivityStateRegistry.updateState(a, ActivityStateRegistry.ChangeType.STOPPED)

        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit

        override fun onActivityDestroyed(a: Activity) =
            ActivityStateRegistry.updateState(a, ActivityStateRegistry.ChangeType.DESTROYED)
    }
}
