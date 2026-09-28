package com.igpulse.xposed.features.media

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.view.View
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.igpulse.xposed.core.Feature
import com.igpulse.xposed.core.IgCore
import com.igpulse.xposed.core.devkit.Unobfuscator
import com.igpulse.xposed.utils.Utils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Adds a "Save" action to the photo, video, story and reel viewers.
 *
 * The trigger is a long-press on the viewer's root view. Instagram rebuilds its overflow menu
 * per media type and there is no single menu to extend, so extending the view is the only
 * approach that survives across media types.
 *
 * Media is *staged* rather than saved on the spot: the viewer resolves a URL, or decodes a
 * bitmap, long before the user presses Save, and the staging hook is the only point at which a
 * higher-resolution variant still exists. Reels are the one type where Instagram only ever
 * hands the viewer a watermarked object, which is reported rather than silently accepted.
 */
class MediaDownloader(
    classLoader: ClassLoader,
    prefs: SharedPreferences) : Feature(classLoader, prefs) {

    private val savePhotos get() = prefs.getBoolean("download_media", false)
    private val saveStories get() = prefs.getBoolean("download_story", false)
    private val saveReels get() = prefs.getBoolean("download_reel", false)

    private enum class Surface(val folder: String) {
        PHOTO("Photos"),
        STORY("Stories"),
        REEL("Reels")
    }

    private data class Staged(
        val surface: Surface,
        val bitmap: Bitmap? = null,
        val videoUrl: String? = null,
        val author: String? = null
    )

    @Volatile
    private var staged: Staged? = null

    @Volatile
    private var currentSurface: Surface? = null

    /**
     * Viewer classes keyed by their *candidate* surfaces.
     *
     * Instagram hosts stories and reels in the same fragment, so a class cannot be mapped to a
     * single surface without making one of the two preferences dead. Candidates are therefore
     * kept per class and [onViewerShown] picks the one the user actually enabled, which is what
     * lets "stories only" and "reels only" work off one resolver pair. Classes are de-duplicated
     * so a shared viewer is hooked exactly once.
     */
    private val viewers: List<Pair<List<Surface>, Class<*>>> by lazy {
        val registered = LinkedHashMap<Class<*>, MutableList<Surface>>()
        fun register(candidate: Class<*>?, vararg surfaces: Surface) {
            if (candidate == null) return
            registered.getOrPut(candidate) { mutableListOf() }.addAll(surfaces)
        }
        register(Unobfuscator.loadMediaViewerFragmentClass(classLoader), Surface.PHOTO)
        register(Unobfuscator.loadReelViewerFragmentClass(classLoader), Surface.REEL)
        register(Unobfuscator.loadStoryViewerClass(classLoader), Surface.STORY)
        registered.map { (cls, surfaces) -> surfaces to cls }
    }

    override fun doHook() {
        if (!savePhotos && !saveStories && !saveReels) return
        stageMediaUrls()
        stageBitmaps()
        installTriggers()
    }

    // ---------------------------------------------------------------------------------------
    // Staging
    // ---------------------------------------------------------------------------------------

    /**
     * The URL factory is Instagram's own, so its output is already the variant the viewer
     * asked for - which is the highest-quality one the client is willing to render.
     */
    private fun stageMediaUrls() {
        val factory = Unobfuscator.loadMediaUrlFactoryClass(classLoader)
        if (factory == null) {
            log("no media url factory resolved; only decoded stills will be savable")
            return
        }

        val urlBuilder = factory.declaredMethods.firstOrNull { method ->
            method.returnType == String::class.java &&
                    method.parameterTypes.any { it == Int::class.javaPrimitiveType }
        } ?: run {
            log("no url builder on ${factory.name}")
            return
        }

        XposedBridge.hookMethod(urlBuilder, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val url = param.result as? String ?: return
                if (!url.startsWith("http")) return
                val surface = currentSurface ?: return
                staged = staged?.copy(surface = surface, videoUrl = url)
                    ?: Staged(surface, videoUrl = url)
                logDebug("staged ${surface.name.lowercase()} video url")
            }
        })
        log("hooked url builder ${Unobfuscator.getMethodDescriptor(urlBuilder)}")
    }

    private fun stageBitmaps() {
        // Reels are video-only on Instagram, so a still only ever matters for photos and stories.
        if (!savePhotos && !saveStories) return
        val factory = Unobfuscator.loadImageFactoryClass(classLoader)
            ?: Unobfuscator.loadMediaUrlFactoryClass(classLoader)
        if (factory == null) return

        val decoder = factory.declaredMethods.firstOrNull { method ->
            method.returnType == Bitmap::class.java && method.parameterCount in 1..4
        } ?: run {
            log("no bitmap producer on ${factory.name}")
            return
        }

        XposedBridge.hookMethod(decoder, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val bitmap = param.result as? Bitmap ?: return
                if (bitmap.isRecycled) return
                val surface = currentSurface ?: return
                if (!isEnabledFor(surface)) return
                staged = staged?.copy(surface = surface, bitmap = bitmap)
                    ?: Staged(surface, bitmap = bitmap)
                logDebug("staged ${surface.name.lowercase()} still ${bitmap.width}x${bitmap.height}")
            }
        })
        log("hooked decoder ${Unobfuscator.getMethodDescriptor(decoder)}")
    }

    /**
     * The author name is what makes a folder of downloads navigable later, so it is taken from
     * the viewer fragment's own username accessor. Only consulted when the naming preference
     * is on, and the first non-blank value the viewer returns is its account.
     */
    private fun stageAuthor() {
        if (!prefs.getBoolean("download_named_after_user", false)) return
        viewers.forEach { (_, fragment) ->
            fragment.declaredMethods
                .filter { it.parameterCount == 0 && it.returnType == String::class.java }
                .take(8)
                .forEach { accessor ->
                    XposedBridge.hookMethod(accessor, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val value = param.result as? String ?: return
                            if (value.isBlank() || staged?.author != null) return
                            staged = staged?.copy(author = value)
                        }
                    })
                }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Trigger
    // ---------------------------------------------------------------------------------------

    /**
     * Attached to the fragment's view lifecycle rather than to the view class, so it also fires
     * for the overlays Instagram swaps in over the media.
     */
    private fun installTriggers() {
        val resolved = viewers
        if (resolved.isEmpty()) {
            log("no viewer fragment resolved, cannot attach a trigger")
            return
        }

        resolved.forEach { (surfaces, fragment) ->
            val viewHook = SEQUENCE_HOOKS.firstNotNullOfOrNull { name ->
                fragment.declaredMethods.firstOrNull { it.name == name }
            } ?: run {
                log("no view lifecycle method on ${fragment.name}")
                return@forEach
            }

            XposedBridge.hookMethod(viewHook, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val root = (param.result as? View)
                        ?: (param.thisObject as? Activity)?.window?.decorView
                        ?: return
                    onViewerShown(surfaces)
                    attachLongPress(root)
                }
            })
            log("hooked ${Unobfuscator.getMethodDescriptor(viewHook)}")
        }

        stageAuthor()
    }

    private fun attachLongPress(root: View) {
        root.setOnLongClickListener {
            val surface = currentSurface ?: return@setOnLongClickListener false
            if (!isEnabledFor(surface)) return@setOnLongClickListener false
            val activity = IgCore.lastResumed ?: return@setOnLongClickListener false
            val current = staged?.takeIf { it.surface == surface }
            activity.runOnUiThread { prompt(activity, current) }
            true
        }
    }

    private fun prompt(activity: Activity, current: Staged?) {
        if (current == null || (current.bitmap == null && current.videoUrl == null)) {
            Toast.makeText(activity, "Nothing captured yet - wait for the media to load", Toast.LENGTH_LONG).show()
            return
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle("IG-Pulse")
            .setItems(arrayOf("Save")) { _, _ ->
                val result = save(current)
                Toast.makeText(
                    activity,
                    if (result.isNullOrEmpty()) "Saved" else "Saved: $result",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------------------------------
    // Saving
    // ---------------------------------------------------------------------------------------

    private fun save(staged: Staged): String? = when {
        staged.bitmap != null -> saveBitmap(staged)
        staged.videoUrl != null -> saveVideo(staged)
        else -> null
    }

    private fun saveBitmap(staged: Staged): String? {
        val name = Utils.generateName(authorFor(staged), "jpg")
        val target = File(Utils.getDestination(staged.surface.folder), name)
        return try {
            ByteArrayOutputStream().use { out ->
                if (!staged.bitmap!!.compress(Bitmap.CompressFormat.JPEG, 95, out)) {
                    return "compress failed"
                }
                Utils.copyToFile(out.toByteArray().inputStream(), target.absolutePath)
            }
        } catch (e: Exception) {
            XposedBridge.log(e)
            e.message
        }
    }

    private fun saveVideo(staged: Staged): String? {
        val url = staged.videoUrl ?: return null
        if (staged.surface == Surface.REEL && isWatermarked(url)) {
            log("only the watermarked reel variant was reachable; saving as served")
        }
        val name = Utils.generateName(authorFor(staged), "mp4")
        val target = File(Utils.getDestination(staged.surface.folder), name)
        return Utils.downloadTo(url, target.absolutePath)
    }

    private fun authorFor(staged: Staged): String? =
        if (prefs.getBoolean("download_named_after_user", false)) {
            staged.author ?: Utils.getMyUsername()
        } else {
            null
        }

    private fun isWatermarked(url: String): Boolean =
        url.contains("watermark", ignoreCase = true)

    // ---------------------------------------------------------------------------------------

    /**
     * Which viewer is on screen decides both the destination folder and whether the feature is
     * enabled. Instagram hosts every viewer inside the same activity, so the activity is no
     * help here - the last viewer whose view was created is the one the user is looking at, and
     * that is what [onViewerShown] records.
     *
     * When a viewer serves more than one surface (stories and reels share one), the enabled
     * candidate wins; with both enabled the first candidate is used.
     */
    private fun onViewerShown(candidates: List<Surface>) {
        if (candidates.isEmpty()) return
        currentSurface = candidates.firstOrNull { isEnabledFor(it) } ?: candidates.first()
    }

    private fun isEnabledFor(surface: Surface): Boolean = when (surface) {
        Surface.PHOTO -> savePhotos
        Surface.STORY -> saveStories
        Surface.REEL -> saveReels
    }

    override fun getPluginName(): String = "MediaDownloader"

    private companion object {
        val SEQUENCE_HOOKS = listOf("onViewCreated", "onCreateView")
    }
}
