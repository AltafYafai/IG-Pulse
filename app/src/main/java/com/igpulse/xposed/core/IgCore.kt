package com.igpulse.xposed.core

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.view.ContextThemeWrapper
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.igpulse.BuildConfig
import com.igpulse.R
import com.igpulse.xposed.bridge.IgIIFace
import com.igpulse.xposed.bridge.client.BridgeClientKt
import com.igpulse.xposed.utils.Utils
import de.robv.android.xposed.XposedBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Instagram-side entry points for module code running inside `com.instagram.android`.
 *
 * The Instagram process has no `WaGlobal` equivalent that is safe to write to, so the
 * cross-process scratch state used by WA-Pulse lives here as a dedicated SharedPreferences
 * file inside the Instagram sandbox. Everything that needs to survive a process restart
 * (remote version allowlist, ignored update hashes, media queue) goes through [getPriv*].
 */
object IgCore {

    private const val PRIV_PREFS = "IGPulseGlobal"

    private var priv: SharedPreferences? = null

    @JvmStatic
    fun initialize(app: Application) {
        priv = app.getSharedPreferences(PRIV_PREFS, Context.MODE_PRIVATE)
    }

    private fun prefs(): SharedPreferences =
        priv ?: throw IllegalStateException("IgCore not initialized")

    @JvmStatic
    fun getPrivString(key: String, fallback: String?): String? =
        runCatching { prefs().getString(key, fallback) }.getOrDefault(fallback)

    @JvmStatic
    fun getPrivBoolean(key: String, fallback: Boolean): Boolean =
        runCatching { prefs().getBoolean(key, fallback) }.getOrDefault(fallback)

    @JvmStatic
    fun setPrivString(key: String, value: String?) {
        runCatching { prefs().edit().putString(key, value).apply() }
    }

    @JvmStatic
    fun setPrivBoolean(key: String, value: Boolean) {
        runCatching { prefs().edit().putBoolean(key, value).apply() }
    }

    /**
     * Module-owned [Context] usable for `getString`/`getDrawable` from the settings app.
     * Instagram's own resources must never be reached through this - it is strictly the
     * module APK's resources.
     */
    lateinit var moduleContext: Context
        private set

    @JvmStatic
    fun initModuleContext(app: Application) {
        val ctx = app.createPackageContext(
            BuildConfig.APPLICATION_ID,
            Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
        )
        moduleContext = ContextThemeWrapper(ctx, R.style.AppTheme)
    }

    /**
     * Shows a module-styled dialog. `AlertDialog.Builder` from the module APK is used so the
     * dialog renders with the module theme instead of Instagram's Material 3 setup.
     */
    @JvmStatic
    fun showAlert(
        activity: Activity,
        title: String? = null,
        message: CharSequence? = null,
        positive: String? = null,
        onPositive: (() -> Unit)? = null,
        negative: String? = null,
        onNegative: (() -> Unit)? = null,
        copyToClipboard: Boolean = false
    ) {
        activity.runOnUiThread {
            val context = ContextThemeWrapper(activity, R.style.AppTheme)
            val builder = AlertDialog.Builder(context)
            if (title != null) builder.setTitle(title)
            if (message != null) builder.setMessage(message)
            if (positive != null) {
                builder.setPositiveButton(positive) { d, _ ->
                    d.dismiss()
                    onPositive?.invoke()
                }
            }
            if (negative != null) builder.setNegativeButton(negative) { d, _ ->
                d.dismiss()
                onNegative?.invoke()
            }
            if (copyToClipboard) {
                builder.setNeutralButton(activity.getString(R.string.copy_to_clipboard)) { _, _ ->
                    val clipboard =
                        activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("igpulse", message))
                    Toast.makeText(activity, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
                }
            }
            builder.show()
        }
    }

    /**
     * Instagram's own "current activity" cannot be tracked from `Activity` lifecycle callbacks
     * alone because it spins up transient activities; the registry is fed by
     * [ActivityStateRegistry] and read by features that need a live window (download dialogs,
     * restart prompts, error surfaces).
     */
    val lastResumed: Activity?
        get() = ActivityStateRegistry.lastResumed

    fun getContext(): Context = moduleContext

    fun requireModuleInstalled(app: Application) {
        try {
            app.packageManager.getPackageInfo(BuildConfig.APPLICATION_ID, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            throw PackageManager.NameNotFoundException(
                app.getString(R.string.alert_module_notfound)
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // Bridge: file access on behalf of the Instagram sandbox
    // ---------------------------------------------------------------------------------------

    @Volatile
    private var bridgeClient: BridgeClientKt? = null

    private val bridgeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * The Instagram process cannot write to shared storage on Android 11+ without the module
     * app acting on its behalf. This lazily spins up the module's `BridgeService` and returns
     * its AIDL interface; `null` means the module app is not installed or not responding, in
     * which case callers should fall back to scoped storage.
     */
    @JvmStatic
    fun getClientBridge(): IgIIFace? {
        val client = bridgeClient ?: BridgeClientKt(Utils.application).also { bridgeClient = it }
        val existing = client.service
        if (existing?.asBinder()?.pingBinder() == true) return existing

        bridgeScope.launch {
            if (!client.connect()) {
                XposedBridge.log("[IG-Pulse] bridge connect failed, falling back to scoped storage")
            }
        }
        return client.service?.takeIf { it.asBinder().pingBinder() }
    }

    @JvmStatic
    fun createRemoteDir(path: String): Boolean = runCatching {
        getClientBridge()?.createDir(path) ?: File(path).mkdirs()
    }.getOrDefault(false)

    @JvmStatic
    fun writeRemoteFile(path: String, input: InputStream): String? = runCatching {
        val dest = File(path)
        dest.parentFile?.let { createRemoteDir(it.absolutePath) }
        getClientBridge()?.openFile(path, true)?.use { pfd ->
            FileOutputStream(pfd.fileDescriptor).use { out -> input.copyTo(out) }
        } ?: input.use { source -> FileOutputStream(dest).use { out -> source.copyTo(out) } }
        Utils.scanFile(dest)
        ""
    }.getOrElse { it.message }

    @JvmStatic
    fun readRemoteFile(path: String): InputStream? = runCatching {
        val file = File(path)
        if (!file.exists()) return null
        getClientBridge()?.openFile(path, false)?.use { pfd ->
            FileInputStream(pfd.fileDescriptor)
        } ?: FileInputStream(file)
    }.getOrNull()
}
