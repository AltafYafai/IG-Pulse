package com.igpulse

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.igpulse.activities.CrashReportActivity
import com.igpulse.xposed.core.FeatureLoader
import rikka.material.app.LocaleDelegate.Companion.defaultLocale
import java.io.File
import java.util.Locale

class App : Application() {

    @SuppressLint("ApplySharedPref")
    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashHandler()
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            setThemeMode(prefs.getString("thememode", "0")!!.toInt())
            changeLanguage(this)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply theme/language", e)
        }
        makePrefsWorldReadable()
    }

    /**
     * The hooked Instagram process reads this app's preferences through `XSharedPreferences`,
     * which needs the file readable outside the module's own UID. `chmod` is the only option
     * that works on stock Android without the module being a system app.
     */
    private fun makePrefsWorldReadable() {
        try {
            val dataDir = filesDir.parentFile ?: return
            dataDir.setReadable(true, false)
            dataDir.setExecutable(true, false)
            val prefsDir = File(dataDir, "shared_prefs")
            if (prefsDir.exists()) {
                prefsDir.setReadable(true, false)
                prefsDir.setExecutable(true, false)
                prefsDir.listFiles()?.forEach {
                    it.setReadable(true, false)
                    it.setWritable(true, false)
                }
            }
            runCatching {
                Runtime.getRuntime().exec(arrayOf("chmod", "-R", "777", dataDir.absolutePath))
            }
        } catch (_: Throwable) {
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val intent = Intent(this, CrashReportActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(CrashReportActivity.EXTRA_CRASH_INFO, buildCrashInfo())
                    putExtra(CrashReportActivity.EXTRA_CRASH_TRACE, Log.getStackTraceString(throwable))
                }
                startActivity(intent)
            } catch (_: Throwable) {
            } finally {
                if (previous != null) previous.uncaughtException(thread, throwable)
                else Runtime.getRuntime().exit(2)
            }
        }
    }

    private fun buildCrashInfo(): String = listOf(
        "${getString(R.string.igpulse_version)}: ${BuildConfig.VERSION_NAME}",
        "${getString(R.string.igpulse_package)}: $packageName",
        "${getString(R.string.crash_android_version)}: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "${getString(R.string.device_model)}: ${Build.MANUFACTURER} ${Build.MODEL}"
    ).joinToString("\n")

    /** Asks the hooked Instagram process to restart itself. */
    fun restartInstagram() {
        val intent = Intent(BuildConfig.APPLICATION_ID + ".INSTAGRAM.RESTART").apply {
            putExtra("PKG", FeatureLoader.PACKAGE_IG)
        }
        sendBroadcast(intent)
    }

    companion object {
        private const val TAG = "IG-Pulse"

        lateinit var instance: App

        @JvmStatic
        fun showRequestStoragePermission(activity: Activity) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.storage_permission)
                .setMessage(R.string.permission_storage)
                .setPositiveButton(R.string.allow) { _, _ ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        activity.startActivity(
                            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                data = Uri.fromParts("package", activity.packageName, null)
                            }
                        )
                    } else {
                        ActivityCompat.requestPermissions(
                            activity,
                            arrayOf(
                                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                android.Manifest.permission.READ_EXTERNAL_STORAGE
                            ),
                            0
                        )
                    }
                }
                .setNegativeButton(R.string.deny) { d, _ -> d.dismiss() }
                .show()
        }

        @JvmStatic
        fun setThemeMode(mode: Int) {
            AppCompatDelegate.setDefaultNightMode(
                when (mode) {
                    1 -> AppCompatDelegate.MODE_NIGHT_YES
                    2 -> AppCompatDelegate.MODE_NIGHT_NO
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }

        @JvmStatic
        fun changeLanguage(context: Context) {
            val forceEnglish = PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("force_english", false)
            defaultLocale = if (forceEnglish) Locale.ENGLISH else Locale.getDefault()
            val res = context.resources
            @Suppress("DEPRECATION")
            res.updateConfiguration(res.configuration, res.displayMetrics)
        }

        @get:SuppressLint("SdCardPath")
        @JvmStatic
        val downloadFolder: File
            get() = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "IG-Pulse"
            ).apply { if (!exists()) mkdirs() }
    }
}
