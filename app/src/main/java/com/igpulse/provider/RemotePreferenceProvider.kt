package com.igpulse.provider

import com.crossbowffs.remotepreferences.RemotePreferenceProvider
import com.igpulse.BuildConfig

/**
 * Read-only view of the module's preferences, exposed through a `ContentProvider` so the
 * hooked Instagram process can read them even when `XSharedPreferences` is blocked by SELinux.
 */
class RemotePreferenceProvider : RemotePreferenceProvider(
    BuildConfig.APPLICATION_ID + ".preferences",
    arrayOf(BuildConfig.APPLICATION_ID + "_preferences")
) {
    override fun checkAccess(prefFileName: String, prefKey: String, write: Boolean): Boolean = !write
}
