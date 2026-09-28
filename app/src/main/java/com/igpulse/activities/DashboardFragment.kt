package com.igpulse.activities

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.igpulse.App
import com.igpulse.BuildConfig
import com.igpulse.R
import com.igpulse.VersionSupport
import com.igpulse.xposed.core.FeatureLoader

/**
 * Landing tab: whether the module is enabled, whether the hooks reached the installed
 * Instagram build, and the versions that decide if they are supposed to.
 *
 * State arrives two ways. Everything that can be answered locally (LSPosed activation, the
 * Instagram package on disk, this APK's own version) is read on demand. Everything that only
 * Instagram knows - did the hooks attach, on which build - comes back over the
 * `RECEIVER_IG` handshake, which the module app requests with `CHECK_IG` and persists so the
 * last known state survives a process death.
 */
class DashboardFragment : Fragment(R.layout.fragment_dashboard) {

    private var checking = false

    private val timeout = Runnable {
        checking = false
        view?.let { render(it) }
    }

    private val handshake = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(KEY_HOOKS_LOADED, intent.getBooleanExtra("HOOKS_LOADED", false))
                .putString(KEY_IG_VERSION, intent.getStringExtra("VERSION"))
                .putInt(KEY_ERRORS, intent.getIntExtra("ERRORS", 0))
                .putString(KEY_REMOTE_SUPPORTED, intent.getStringExtra("SUPPORTED"))
                .putLong(KEY_SEEN_AT, System.currentTimeMillis())
                .apply()
            checking = false
            view?.removeCallbacks(timeout)
            view?.let { render(it) }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ContextCompat.registerReceiver(
            requireContext(), handshake,
            IntentFilter("${BuildConfig.APPLICATION_ID}.RECEIVER_IG"),
            ContextCompat.RECEIVER_EXPORTED
        )
        view.findViewById<View>(R.id.refreshBtn).setOnClickListener { checkStatus() }
        view.findViewById<View>(R.id.restartBtn).setOnClickListener {
            App.instance.restartInstagram()
        }
        render(view)
    }

    override fun onResume() {
        super.onResume()
        checkStatus()
    }

    override fun onDestroyView() {
        view?.removeCallbacks(timeout)
        runCatching { requireContext().unregisterReceiver(handshake) }
        super.onDestroyView()
    }

    /** Asks the hooked Instagram process to answer, then gives it a moment to reply. */
    private fun checkStatus() {
        val root = view ?: return
        checking = true
        render(root)
        requireContext().sendBroadcast(
            Intent("${BuildConfig.APPLICATION_ID}.CHECK_IG")
                .setPackage(FeatureLoader.PACKAGE_IG)
        )
        root.removeCallbacks(timeout)
        root.postDelayed(timeout, 2_500)
    }

    private fun render(root: View) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val moduleActive = (activity as? MainActivity)?.isXposedEnabled() ?: false
        val installed = installedInstagramVersion()
        val supported = supportedVersions(prefs)
        val hooksLoaded = prefs.getBoolean(KEY_HOOKS_LOADED, false)
        val errors = prefs.getInt(KEY_ERRORS, 0)
        val seenAt = prefs.getLong(KEY_SEEN_AT, 0L)
        val fresh = seenAt > 0 && System.currentTimeMillis() - seenAt <= FRESH_MS
        val hookedVersion = prefs.getString(KEY_IG_VERSION, null) ?: installed

        renderHero(root, moduleActive, installed, hooksLoaded, errors, fresh, seenAt)

        bindRow(
            root, R.id.rowModule,
            title = getString(R.string.row_module),
            subtitle = getString(if (moduleActive) R.string.row_module_active else R.string.row_module_inactive),
            chip = getString(if (moduleActive) R.string.state_active else R.string.state_inactive),
            tone = if (moduleActive) TONE_OK else TONE_ERROR
        )

        val hookTone = when {
            hooksLoaded && errors > 0 -> TONE_WARN
            hooksLoaded && fresh -> TONE_OK
            else -> TONE_ERROR
        }
        bindRow(
            root, R.id.rowHooks,
            title = getString(R.string.row_hooks),
            subtitle = when {
                hooksLoaded && fresh ->
                    getString(R.string.row_hooks_loaded, hookedVersion ?: "?", relative(seenAt))
                else -> getString(R.string.row_hooks_pending)
            },
            chip = when {
                hooksLoaded && errors > 0 -> getString(R.string.state_errors)
                hooksLoaded && fresh -> getString(R.string.state_loaded)
                else -> getString(R.string.state_not_loaded)
            },
            tone = hookTone
        )

        if (installed == null) {
            bindRow(
                root, R.id.rowInstagram,
                title = getString(R.string.row_instagram),
                subtitle = getString(R.string.row_not_installed),
                chip = null,
                tone = TONE_WARN
            )
        } else {
            val supportedNow = VersionSupport.isVersionSupported(installed, supported)
            bindRow(
                root, R.id.rowInstagram,
                title = getString(R.string.row_instagram),
                subtitle = installed,
                chip = getString(if (supportedNow) R.string.state_supported else R.string.state_unsupported),
                tone = if (supportedNow) TONE_OK else TONE_ERROR
            )
        }

        bindRow(
            root, R.id.rowSupported,
            title = getString(R.string.row_supported_versions),
            subtitle = supported.joinToString(" · "),
            chip = null,
            tone = TONE_NEUTRAL
        )

        bindRow(
            root, R.id.rowModuleVersion,
            title = getString(R.string.row_module_version),
            subtitle = BuildConfig.VERSION_NAME,
            chip = null,
            tone = TONE_NEUTRAL
        )

        bindRow(
            root, R.id.rowAndroid,
            title = getString(R.string.crash_android_version),
            subtitle = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            chip = null,
            tone = TONE_NEUTRAL,
            hideDot = true
        )

        bindRow(
            root, R.id.rowDevice,
            title = getString(R.string.device_model),
            subtitle = "${Build.MANUFACTURER} ${Build.MODEL}",
            chip = null,
            tone = TONE_NEUTRAL,
            hideDot = true
        )
    }

    private fun renderHero(
        root: View,
        moduleActive: Boolean,
        installed: String?,
        hooksLoaded: Boolean,
        errors: Int,
        fresh: Boolean,
        seenAt: Long
    ) {
        val (title, message, tone) = when {
            !moduleActive -> Triple(
                getString(R.string.dashboard_module_off_title),
                getString(R.string.dashboard_module_off_message),
                TONE_ERROR
            )

            installed == null -> Triple(
                getString(R.string.dashboard_instagram_missing_title),
                getString(R.string.dashboard_instagram_missing_message),
                TONE_WARN
            )

            checking -> Triple(
                getString(R.string.dashboard_checking),
                getString(R.string.dashboard_hooks_pending_message),
                TONE_NEUTRAL
            )

            hooksLoaded && errors > 0 -> Triple(
                getString(R.string.dashboard_hooks_error_title),
                getString(R.string.dashboard_hooks_error_message, errors),
                TONE_WARN
            )

            hooksLoaded && fresh -> Triple(
                getString(R.string.dashboard_ok_title),
                getString(R.string.dashboard_ok_message),
                TONE_OK
            )

            else -> Triple(
                getString(R.string.dashboard_hooks_missing_title),
                getString(R.string.dashboard_hooks_missing_message),
                TONE_WARN
            )
        }

        root.findViewById<TextView>(R.id.heroTitle).text = title
        root.findViewById<TextView>(R.id.heroMessage).text = message
        root.findViewById<TextView>(R.id.heroDetail).text = when {
            checking -> getString(R.string.dashboard_checking)
            seenAt > 0 -> getString(R.string.dashboard_last_seen, relative(seenAt))
            else -> getString(R.string.dashboard_no_handshake)
        }
        tint(root.findViewById(R.id.heroDot), tone)
    }

    private fun bindRow(
        root: View,
        rowId: Int,
        title: String,
        subtitle: String,
        chip: String?,
        tone: Int,
        hideDot: Boolean = false
    ) {
        val row = root.findViewById<View>(rowId)
        row.findViewById<TextView>(R.id.rowTitle).text = title
        row.findViewById<TextView>(R.id.rowSubtitle).text = subtitle
        tint(row.findViewById(R.id.rowDot), tone, hideDot)

        val chipView = row.findViewById<TextView>(R.id.rowChip)
        if (chip == null) {
            chipView.visibility = View.GONE
        } else {
            chipView.visibility = View.VISIBLE
            chipView.text = chip
            chipView.setBackgroundResource(
                when (tone) {
                    TONE_OK -> R.drawable.bg_pulse_badge
                    TONE_ERROR -> R.drawable.bg_pulse_badge_inactive
                    TONE_WARN -> R.drawable.bg_pulse_badge_warning
                    else -> R.drawable.bg_chip_glass
                }
            )
            chipView.setTextColor(
                if (tone == TONE_NEUTRAL) color(R.color.text_primary) else color(R.color.text_on_gradient)
            )
        }
    }

    private fun tint(view: View, tone: Int, hide: Boolean = false) {
        view.visibility = if (hide) View.INVISIBLE else View.VISIBLE
        ViewCompat.setBackgroundTintList(
            view,
            ColorStateList.valueOf(
                color(
                    when (tone) {
                        TONE_OK -> R.color.status_active
                        TONE_ERROR -> R.color.status_inactive
                        TONE_WARN -> R.color.status_warning
                        else -> R.color.text_tertiary
                    }
                )
            )
        )
    }

    private fun color(id: Int) = requireContext().getColor(id)

    private fun relative(at: Long): CharSequence =
        DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)

    private fun installedInstagramVersion(): String? = runCatching {
        requireContext().packageManager.getPackageInfo(FeatureLoader.PACKAGE_IG, 0).versionName
    }.getOrNull()

    private fun supportedVersions(prefs: android.content.SharedPreferences): List<String> {
        val local = resources.getStringArray(R.array.supported_versions).toList()
        val remote = prefs.getString(KEY_REMOTE_SUPPORTED, null)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            .orEmpty()
        return if (remote.isEmpty()) local else (local + remote).distinct()
    }

    companion object {
        /** Older than this and the handshake says nothing about the current Instagram run. */
        private const val FRESH_MS = 10 * 60 * 1000L

        private const val KEY_HOOKS_LOADED = "dashboard_hooks_loaded"
        private const val KEY_IG_VERSION = "dashboard_ig_version"
        private const val KEY_ERRORS = "dashboard_errors"
        private const val KEY_REMOTE_SUPPORTED = "dashboard_remote_supported"
        private const val KEY_SEEN_AT = "dashboard_seen_at"

        private const val TONE_OK = 0
        private const val TONE_ERROR = 1
        private const val TONE_WARN = 2
        private const val TONE_NEUTRAL = 3
    }
}
