package com.igpulse.activities

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.igpulse.App
import com.igpulse.R
import com.igpulse.databinding.ActivityMainBinding
import com.igpulse.xposed.core.devkit.UnobfuscatorCache

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val pages = listOf(
        R.string.dashboard to DashboardFragment::class.java,
        R.string.privacy to PrivacyFragment::class.java,
        R.string.media to MediaFragment::class.java,
        R.string.customization to CustomizationFragment::class.java,
        R.string.utilities to UtilitiesFragment::class.java
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.viewPager.adapter = PageAdapter(this)
        binding.viewPager.offscreenPageLimit = pages.size

        binding.navView.setOnItemSelectedListener { item ->
            val index = pages.indexOfFirst { entry ->
                item.itemId == idFor(entry.second)
            }
            if (index >= 0) {
                binding.viewPager.setCurrentItem(index, false)
                true
            } else {
                false
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.viewPager.currentItem != 0) {
                    binding.viewPager.setCurrentItem(0, false)
                    binding.navView.selectedItemId = idFor(pages[0].second)
                } else {
                    finish()
                }
            }
        })

        if (!isModuleActive()) {
            // The dashboard is the first tab and carries the same state, so the blocking
            // dialog is only worth showing once - it used to repeat on every launch.
            val shown = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean("module_notice_shown", false)
            if (!shown) {
                binding.root.post {
                    com.igpulse.xposed.core.IgCore.showAlert(
                        this,
                        title = getString(R.string.module_not_installed),
                        message = getString(R.string.lsposed_notice),
                        positive = getString(R.string.ok)
                    )
                }
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                    .edit().putBoolean("module_notice_shown", true).apply()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.header_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.menu_restart -> {
            App.instance.restartInstagram()
            true
        }

        R.id.menu_about -> {
            startActivity(Intent(this, AboutActivity::class.java))
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    /**
     * Faked to `true` by [com.igpulse.IGPulse] when the module is loaded into this app, which
     * is how the settings UI knows a hook actually reached the module process.
     */
    fun isXposedEnabled(): Boolean = false

    private fun isModuleActive(): Boolean = isXposedEnabled()

    private fun idFor(fragment: Class<*>): Int = when (fragment) {
        DashboardFragment::class.java -> R.id.navigation_dashboard
        PrivacyFragment::class.java -> R.id.navigation_privacy
        MediaFragment::class.java -> R.id.navigation_media
        CustomizationFragment::class.java -> R.id.navigation_customization
        else -> R.id.navigation_utilities
    }

    private class PageAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 5

        override fun createFragment(position: Int): androidx.fragment.app.Fragment = when (position) {
            0 -> DashboardFragment()
            1 -> PrivacyFragment()
            2 -> MediaFragment()
            3 -> CustomizationFragment()
            else -> UtilitiesFragment()
        }
    }
}

/** Shared plumbing for the four settings pages: header, restart hint, preference inflation. */
abstract class BasePreferenceFragment : PreferenceFragmentCompat() {

    protected fun restartHint() {
        findPreference<Preference>("restart_instagram")?.setOnPreferenceClickListener {
            App.instance.restartInstagram()
            true
        }
        findPreference<Preference>("clear_cache")?.setOnPreferenceClickListener {
            UnobfuscatorCache.clearCache(requireContext())
            Toast.makeText(requireContext(), R.string.cleared_cache, Toast.LENGTH_SHORT).show()
            true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        restartHint()
    }
}

class PrivacyFragment : BasePreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) =
        setPreferencesFromResource(R.xml.pref_privacy, rootKey)
}

class MediaFragment : BasePreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) =
        setPreferencesFromResource(R.xml.pref_media, rootKey)
}

class CustomizationFragment : BasePreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) =
        setPreferencesFromResource(R.xml.pref_customization, rootKey)
}

class UtilitiesFragment : BasePreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) =
        setPreferencesFromResource(R.xml.pref_utilities, rootKey)
}
