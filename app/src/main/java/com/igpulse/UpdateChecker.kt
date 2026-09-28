package com.igpulse

import android.app.Activity
import com.igpulse.xposed.core.IgCore
import com.igpulse.xposed.utils.Utils
import de.robv.android.xposed.XposedBridge
import io.noties.markwon.Markwon
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Checks GitHub releases for a newer module build, and refreshes the remote Instagram version
 * allowlist so a new Instagram beta does not require shipping an APK.
 *
 * Runs inside the Instagram process, so it can only surface UI once a window exists.
 */
class UpdateChecker(private val activity: Activity) : Runnable {

    companion object {
        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        }
    }

    override fun run() {
        runCatching { refreshRemoteSupportedVersions() }
        runCatching { checkRelease() }
    }

    private fun checkRelease() {
        val betaChannel = IgCore.getPrivBoolean("update_beta_channel", false)
        val url = if (betaChannel) BuildConfig.RELEASES_API_ALL else BuildConfig.RELEASES_API_LATEST
        val request = okhttp3.Request.Builder().url(url).build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return
            val body = response.body.string()
            val release = if (betaChannel) {
                JSONArray(body).optJSONObject(0) ?: return
            } else {
                JSONObject(body)
            }

            val tagName = release.optString("tag_name", "").trim()
            if (tagName.isEmpty()) return
            val hash = tagName.substringAfter('-', tagName.removePrefix("v")).trim()
            if (hash.isEmpty()) return

            val installed = activity.packageManager
                .getPackageInfo(BuildConfig.APPLICATION_ID, 0).versionName.orEmpty()
            if (installed.lowercase().contains(hash.lowercase())) return
            if (IgCore.getPrivString("ignored_version", "") == hash) return

            val changelog = release.optString("body", "").trim()
            val publishedAt = release.optString("published_at", "")
            val downloadUrl = selectApkUrl(release)
                ?: release.optString("html_url", BuildConfig.RELEASES_PAGE)

            activity.runOnUiThread {
                IgCore.showAlert(
                    activity,
                    title = activity.getString(R.string.update_available),
                    message = buildString {
                        appendLine("${activity.getString(R.string.version)}: `$tagName`")
                        formatPublishedDate(publishedAt).takeIf { it.isNotEmpty() }?.let {
                            appendLine("${activity.getString(R.string.released)}: $it")
                        }
                        appendLine()
                        appendLine(changelog)
                    }.let { Markwon.create(activity).toMarkdown(it) },
                    positive = activity.getString(R.string.update_now),
                    onPositive = { Utils.openLink(activity, downloadUrl) },
                    negative = activity.getString(R.string.ignore),
                    onNegative = {
                        IgCore.setPrivString("ignored_version", hash)
                    }
                )
            }
        }
    }

    private fun selectApkUrl(release: JSONObject): String? {
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name", "")
            if (name.endsWith(".apk", ignoreCase = true)) return asset.optString("browser_download_url", "")
        }
        return null
    }

    private fun refreshRemoteSupportedVersions() {
        val request = okhttp3.Request.Builder()
            .url(BuildConfig.SUPPORTED_VERSIONS_RAW)
            .header("Cache-Control", "no-cache")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return
            val body = response.body.string()
            val versions = Regex("""<item>(\d{3}\.\d+\.\d+(\.\d+)?(\.xx)?)</item>""")
                .findAll(body)
                .map { it.groupValues[1] }
                .distinct()
                .toList()
            if (versions.isEmpty()) return
            val joined = versions.joinToString(",")
            IgCore.setPrivString("remote_supported_versions", joined)
            IgCore.setPrivString("remote_versions_fetched_at", System.currentTimeMillis().toString())
            runCatching {
                Utils.xprefs.edit().putString("remote_supported_versions", joined).apply()
            }
            XposedBridge.log("[IG-Pulse] refreshed allowlist: $versions")
        }
    }

    private fun formatPublishedDate(isoDate: String?): String {
        if (isoDate.isNullOrEmpty()) return ""
        return runCatching {
            val parsed = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).parse(isoDate)
            SimpleDateFormat("MMM dd, yyyy", Locale.US).format(parsed!!)
        }.getOrDefault("")
    }
}
