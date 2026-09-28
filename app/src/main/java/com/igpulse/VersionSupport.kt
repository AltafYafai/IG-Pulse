package com.igpulse

/**
 * The version policy shared by the settings UI and the hooked Instagram process.
 *
 * It lives outside `xposed.core` on purpose: the module APK evaluates it before Instagram has
 * been seen at all, and the Xposed API is `compileOnly`, so this object must stay free of any
 * reference the plain app process cannot resolve.
 */
object VersionSupport {

    /** First Instagram version this module shipped against. */
    val BASELINE = Triple(373, 0, 0)

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
}
