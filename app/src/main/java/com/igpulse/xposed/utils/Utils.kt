package com.igpulse.xposed.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.igpulse.App
import com.igpulse.xposed.core.FeatureLoader
import com.igpulse.xposed.core.IgCore
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object Utils {
    const val CHANNEL_ID = "ig_pulse"

    lateinit var xprefs: SharedPreferences
    private val ids = HashMap<String?, Int?>()
    lateinit var appClassLoader: ClassLoader

    fun init() {
        val context: Application = application
        val notificationManager = NotificationManagerCompat.from(context)
        val channel =
            NotificationChannel(CHANNEL_ID, "IG-Pulse", NotificationManager.IMPORTANCE_HIGH)
        notificationManager.createNotificationChannel(channel)
    }


    @JvmStatic
    val application: Application
        get() = FeatureLoader.mApp ?: App.instance!!

    fun getString(id: Int): String {
        return application.getString(id)
    }


    val executor: ExecutorService by lazy {
        Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    }


    @JvmStatic
    fun doRestart(context: Context): Boolean {
        val packageManager = context.packageManager
        val intent =
            packageManager.getLaunchIntentForPackage(context.packageName) ?: return false
        val componentName = intent.component
        val mainIntent = Intent.makeRestartActivityTask(componentName)
        mainIntent.setPackage(context.packageName)
        context.startActivity(mainIntent)
        Runtime.getRuntime().exit(0)
        return true
    }

    /**
     * Retrieves the resource ID by name and type.
     * Uses caching to improve performance for repeated lookups.
     * 
     * @param name The resource name to look up
     * @param type The resource type (e.g., "id", "drawable", "layout", "string")
     * @return The resource ID or -1 if not found or an error occurred
     */
    @JvmStatic
    @SuppressLint("DiscouragedApi")
    fun getID(name: String?, type: String?): Int {
        if (TextUtils.isEmpty(name) || TextUtils.isEmpty(type)) {
            return -1
        }

        val key = type + "_" + name

        synchronized(ids) {
            if (ids.containsKey(key)) {
                val cachedId = ids[key]
                return cachedId ?: -1
            }
        }

        try {
            val app: Application = application
            val context = app.applicationContext
            val id = context.resources.getIdentifier(name, type, app.packageName)

            synchronized(ids) {
                ids.put(key, id)
            }

            return id
        } catch (e: Exception) {
            XposedBridge.log("Error getting resource ID: type=" + type + ", name=" + name + ", error: " + e.message)
            return -1
        }
    }

    @JvmStatic
    fun dipToPixels(dipValue: Int): Int {
        val metrics = FeatureLoader.mApp!!.resources.displayMetrics
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dipValue.toFloat(), metrics)
            .toInt()
    }


    @JvmStatic
    fun dipToPixels(dipValue: Float): Int {
        val metrics = FeatureLoader.mApp!!.resources.displayMetrics
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dipValue, metrics).toInt()
    }

    @SuppressLint("SdCardPath")
    fun getDownloadRoot(): File {
        val base = xprefs.getString("download_local", "/sdcard/Download")
        val root = File(base, "IG-Pulse")
        IgCore.createRemoteDir(root.absolutePath)
        return root
    }

    @SuppressLint("SdCardPath")
    fun getDestination(subFolder: String): String {
        val custom = xprefs.getString("download_dir_$subFolder", null)
        val target = if (!custom.isNullOrBlank()) File(custom) else File(getDownloadRoot(), subFolder)
        IgCore.createRemoteDir(target.absolutePath)
        return target.absolutePath + "/"
    }

    fun copyToFile(input: InputStream, destPath: String): String? =
        IgCore.writeRemoteFile(destPath, input)

    fun downloadTo(url: String, destPath: String): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        conn.inputStream.use { IgCore.writeRemoteFile(destPath, it) }
    }.getOrElse {
        XposedBridge.log(it)
        it.message
    }

    @JvmStatic
    @JvmOverloads
    fun showToast(message: String?, length: Int = 0) {
        if (message == null) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(application, message, length).show()
        } else {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    application,
                    message,
                    length
                ).show()
            }
        }
    }

    fun generateName(username: String?, fileFormat: String?): String =
        toValidFileName(username ?: "igpulse") + "_" + SimpleDateFormat(
            "yyyyMMdd-HHmmss",
            Locale.getDefault()
        ).format(Date()) + "." + fileFormat


    fun toValidFileName(input: String): String {
        return input.replace("[:\\\\/*\"?|<>']".toRegex(), " ")
    }

    fun scanFile(file: File) {
        MediaScannerConnection.scanFile(
            application,
            arrayOf<String>(file.absolutePath),
            arrayOf<String?>(MimeTypeUtils.getMimeTypeFromExtension(file.absolutePath))
        ) { _: String?, _: Uri? -> }
    }

    fun getMyUsername(): String = runCatching {
        getViewerUsername()
    }.getOrDefault("")

    private fun getViewerUsername(): String {
        val app = FeatureLoader.mApp!!
        val direct = app.getSharedPreferences("direct", Context.MODE_PRIVATE)
        listOf("last_login_username", "viewer_username").forEach { key ->
            direct.getString(key, null)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        val sharedPrefs = app.getSharedPreferences("shared_prefs", Context.MODE_PRIVATE)
        listOf("ig_username_key", "username").forEach { key ->
            sharedPrefs.getString(key, null)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return ""
    }


    @JvmStatic
    fun <T> binderLocalScope(block: BinderLocalScopeBlock<T?>): T? {
        val identity = Binder.clearCallingIdentity()
        try {
            return block.execute()
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    @SuppressLint("MissingPermission")
    fun showNotification(title: String?, content: String?) {
        val context: Application = application
        val notificationManager = NotificationManagerCompat.from(context)
        val channel =
            NotificationChannel(CHANNEL_ID, "IG-Pulse", NotificationManager.IMPORTANCE_HIGH)
        notificationManager.createNotificationChannel(channel)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.mipmap.sym_def_app_icon)
            .setContentTitle(title)
            .setContentText(content)
            .setAutoCancel(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
        notificationManager.notify(Random().nextInt(), notification.build())
    }

    @JvmStatic
    fun openLink(mActivity: Activity, url: String?) {
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        mActivity.startActivity(browserIntent)
    }

    fun interface BinderLocalScopeBlock<T> {
        fun execute(): T?
    }
}
