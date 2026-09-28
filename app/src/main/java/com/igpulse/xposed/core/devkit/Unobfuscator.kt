package com.igpulse.xposed.core.devkit

import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves obfuscated Instagram internals through DexKit.
 *
 * Instagram is shipped through R8 with aggressive renaming, but two families of anchors stay
 * stable across releases:
 *
 *  1. **GraphQL operation names** (`direct_v2/get_presence`, `direct_v2/seen_items`, ...).
 *     These are protocol strings, not symbols - the server rejects requests without them, so
 *     they are the single most durable anchor in the APK.
 *  2. **Reflectively-referenced class names** (anything Instagram instantiates by name from
 *     manifest, resources or `Class.forName`). These survive obfuscation.
 *
 * Every resolver here takes an ordered anchor list and returns `null` - never throws - when
 * nothing matches. Features skip themselves on `null` and report it, so a stale anchor
 * degrades to "one feature missing" instead of taking the whole module down with it.
 */
object Unobfuscator {

    private lateinit var bridge: DexKitBridge

    val cacheClasses = ConcurrentHashMap<String, Class<*>>()

    init {
        System.loadLibrary("dexkit")
    }

    @JvmStatic
    fun initWithPath(path: String): Boolean = try {
        bridge = DexKitBridge.create(path)
        true
    } catch (_: Exception) {
        false
    }

    // ---------------------------------------------------------------------------------------
    // Generic engine
    // ---------------------------------------------------------------------------------------

    @Throws(Exception::class)
    @JvmStatic
    fun findFirstMethodUsingStrings(
        classLoader: ClassLoader,
        type: StringMatchType,
        vararg strings: String
    ): Method? = bridge.findMethod {
        matcher { strings.forEach { addUsingString(it, type) } }
    }.firstNotNullOfOrNull { it.takeIf(MethodData::isMethod)?.getMethodInstance(classLoader) }

    @JvmStatic
    fun findAllMethodUsingStrings(
        classLoader: ClassLoader,
        type: StringMatchType,
        vararg strings: String
    ): Array<Method> = bridge.findMethod {
        matcher { strings.forEach { addUsingString(it, type) } }
    }.filter { it.isMethod }
        .mapNotNull { runCatching { it.getMethodInstance(classLoader) }.getOrNull() }
        .toTypedArray()

    @Throws(Exception::class)
    @JvmStatic
    fun findFirstClassUsingStrings(
        classLoader: ClassLoader,
        type: StringMatchType,
        vararg strings: String
    ): Class<*>? = bridge.findClass {
        matcher { strings.forEach { addUsingString(it, type) } }
    }.firstNotNullOfOrNull { runCatching { it.getInstance(classLoader) }.getOrNull() }

    @JvmStatic
    fun findFirstClassUsingName(classLoader: ClassLoader, type: StringMatchType, name: String): Class<*>? =
        bridge.findClass { matcher { className(name, type) } }
            .firstNotNullOfOrNull { runCatching { it.getInstance(classLoader) }.getOrNull() }

    /**
     * Tries each anchor in order and returns the first hit. Logging every miss is deliberate:
     * when a feature breaks, the logcat line names the exact anchor that stopped matching,
     * which is what you need to re-pin it against a new Instagram build.
     */
    private fun <T> resolve(feature: String, anchors: List<String>, type: StringMatchType, hit: (String) -> T?): T? {
        for (anchor in anchors) {
            val result = runCatching { hit(anchor) }.getOrNull()
            if (result != null) {
                XposedBridge.log("[IG-Pulse] $feature <- \"$anchor\" -> ${describe(result)}")
                return result
            }
        }
        XposedBridge.log(
            "[IG-Pulse] $feature unresolved. None of the anchors matched: ${anchors.joinToString()}"
        )
        return null
    }

    private fun describe(v: Any?): String = when (v) {
        is Method -> "${v.declaringClass.simpleName}->${v.name}(${v.parameterTypes.joinToString(", ") { it.simpleName }})"
        is Field -> "${v.declaringClass.simpleName}->${v.name}"
        is Constructor<*> -> "${v.declaringClass.simpleName}-><init>(${v.parameterTypes.joinToString(", ") { it.simpleName }})"
        is Class<*> -> v.name
        else -> v.toString()
    }

    @JvmStatic
    fun getMethodDescriptor(method: Method?): String? = method?.let {
        it.declaringClass.name + "->" + it.name + "(" +
                it.parameterTypes.joinToString(",") { p -> p.name } + ")"
    }

    @JvmStatic
    fun getFieldDescriptor(field: Field): String =
        field.declaringClass.name + "->" + field.name + ":" + field.type.name

    @JvmStatic
    fun convertRealMethod(methodData: MethodData, classLoader: ClassLoader): Method? =
        runCatching { methodData.getMethodInstance(classLoader) }.getOrNull()

    @JvmStatic
    fun convertRealClass(classData: ClassData, classLoader: ClassLoader): Class<*>? =
        runCatching { classData.getInstance(classLoader) }.getOrNull()

    // ---------------------------------------------------------------------------------------
    // Direct-thread presence / receipts
    // ---------------------------------------------------------------------------------------

    /**
     * The `direct_v2/get_presence` request. Hiding read receipts is done by dropping the
     * `item_ids` payload on the outbound `direct_v2/seen_items` mutation instead, so this
     * resolver exists for the "freeze last seen" and "Active Now" features.
     */
    @JvmStatic
    fun loadGetPresenceMethod(loader: ClassLoader): Method? =
        UnobfuscatorCache.getInstance().getMethodOrNull(loader) {
            resolve(
                "getPresence",
                listOf("direct_v2/get_presence", "direct_v2/get_presence_active_now", "direct_v2/thread_presence"),
                StringMatchType.Contains
            ) { anchor -> findFirstMethodUsingStrings(loader, StringMatchType.Contains, anchor) }
        }

    /**
     * The `direct_v2/seen_items` mutation. IG sends it both to *report* that you read a thread
     * and to *request* the read state of the other side, so hooks on this method have to
     * distinguish direction by inspecting arguments rather than by hooking a distinct class.
     */
    @JvmStatic
    fun loadSeenItemsMethod(loader: ClassLoader): Method? =
        UnobfuscatorCache.getInstance().getMethodOrNull(loader) {
            resolve(
                "seenItems",
                listOf("direct_v2/seen_items", "direct_v2/get_seen_state", "direct_v2/seen"),
                StringMatchType.Contains
            ) { anchor -> findFirstMethodUsingStrings(loader, StringMatchType.Contains, anchor) }
        }

    /** `direct_v2/send_seen_state` - the older mutation, still present in some builds. */
    @JvmStatic
    fun loadSendSeenStateMethod(loader: ClassLoader): Method? =
        UnobfuscatorCache.getInstance().getMethodOrNull(loader) {
            resolve(
                "sendSeenState",
                listOf("direct_v2/send_seen_state", "direct_v2/threads_broadcast", "direct_v2/seen_items/"),
                StringMatchType.Contains
            ) { anchor -> findFirstMethodUsingStrings(loader, StringMatchType.Contains, anchor) }
        }

    /** Typing indicator transport. IG sends `direct_v2/activity_indicator` while composing. */
    @JvmStatic
    fun loadActivityIndicatorMethod(loader: ClassLoader): Method? =
        UnobfuscatorCache.getInstance().getMethodOrNull(loader) {
            resolve(
                "activityIndicator",
                listOf("direct_v2/activity_indicator", "direct_v2/typing_indicator", "direct_v2/activity_indicator/"),
                StringMatchType.Contains
            ) { anchor -> findFirstMethodUsingStrings(loader, StringMatchType.Contains, anchor) }
        }

    /** The payload model for an activity indicator, used to identify the outgoing thread id. */
    @JvmStatic
    fun loadActivityIndicatorClass(loader: ClassLoader): Class<*>? =
        UnobfuscatorCache.getInstance().getClassOrNull(loader) {
            resolve(
                "activityIndicatorClass",
                listOf("ActivityIndicatorResponse", "TypingIndicatorResponse"),
                StringMatchType.Contains
            ) { anchor -> findFirstClassUsingStrings(loader, StringMatchType.Contains, anchor) }
        }

    // ---------------------------------------------------------------------------------------
    // Story views
    // ---------------------------------------------------------------------------------------

    /** The mutation that records a story view. */
    @JvmStatic
    fun loadStorySeenMethod(loader: ClassLoader): Method? =
        UnobfuscatorCache.getInstance().getMethodOrNull(loader) {
            resolve(
                "storySeen",
                listOf(
                    "direct_v2/reels_media_seen",
                    "direct_v2/reels_media_seen/",
                    "story_seen"
                ),
                StringMatchType.Contains
            ) { anchor -> findFirstMethodUsingStrings(loader, StringMatchType.Contains, anchor) }
        }

    /**
     * Story tray view model. Hiding story views works by filtering the tray adapter's data
     * source *before* it is submitted to the RecyclerView, so this class needs a `Long` id
     * field and a timestamp; both the "All Stories" and per-user tray adapters derive from it.
     */
    @JvmStatic
    fun loadStoryTrayItemClass(loader: ClassLoader): Class<*>? =
        UnobfuscatorCache.getInstance().getClassOrNull(loader) {
            resolve(
                "storyTrayItem",
                listOf("ReelMediaViewerFragment", "StoryTrayItemViewModel", "story_tray_recycler_item"),
                StringMatchType.Contains
            ) { anchor -> findFirstClassUsingStrings(loader, StringMatchType.Contains, anchor) }
        }

    @JvmStatic
    fun loadStoryViewerClass(loader: ClassLoader): Class<*>? =
        UnobfuscatorCache.getInstance().getClassOrNull(loader) {
            findFirstClassUsingName(loader, StringMatchType.EndsWith, "ReelMediaViewerFragment")
        }

    // ---------------------------------------------------------------------------------------
    // Media / downloads
    // ---------------------------------------------------------------------------------------

    /**
     * The bitmap/video URL factory. IG builds downloadable URLs here for every image and
     * video rendered in a viewer, which is where the highest-resolution variant can be
     * swapped in before Instagram's own downscale.
     */
    @JvmStatic
    fun loadMediaUrlFactoryClass(loader: ClassLoader): Class<*>? =
        UnobfuscatorCache.getInstance().getClassOrNull(loader) {
            resolve(
                "mediaUrlFactory",
                listOf("com.instagram.media.service.MediaConfig", "VideoUrlRequest", "AutoMediaTree", "ImageFactory"),
                StringMatchType.Contains
            ) { anchor -> findFirstClassUsingStrings(loader, StringMatchType.Contains, anchor) }
        }

    @JvmStatic
    fun loadImageFactoryClass(loader: ClassLoader): Class<*>? =
        UnobfuscatorCache.getInstance().getClassOrNull(loader) {
            resolve(
                "imageFactory",
                listOf("com.instagram.common.util.ImageUtils", "ImageFactory", "ImageFactoryKt"),
                StringMatchType.Contains
            ) { anchor -> findFirstClassUsingStrings(loader, StringMatchType.Contains, anchor) }
        }

    /** The `?__a=` / `?__d=` GraphQL shortcode reader used to resolve a permalink to a media id. */
    @JvmStatic
    fun loadMediaIdResolverMethod(loader: ClassLoader): Method? =
        UnobfuscatorCache.getInstance().getMethodOrNull(loader) {
            resolve(
                "mediaIdResolver",
                listOf("shortcode_media/", "shortcode", "graphql/query"),
                StringMatchType.Contains
            ) { anchor -> findFirstMethodUsingStrings(loader, StringMatchType.Contains, anchor) }
        }

    // ---------------------------------------------------------------------------------------
    // Reels / viewer
    // ---------------------------------------------------------------------------------------

    @JvmStatic
    fun loadReelsTabFragmentClass(loader: ClassLoader): Class<*>? =
        UnobfuscatorCache.getInstance().getClassOrNull(loader) {
            resolve(
                "reelsTabFragment",
                listOf(
                    "com.instagram.tab.fragment.ReelsTabFragment",
                    "com.instagram.reels.ReelsFragment",
                    "com.instagram.tab.fragment.ClipsTabFragment"
                ),
                StringMatchType.Contains
            ) { anchor -> findFirstClassUsingStrings(loader, StringMatchType.Contains, anchor) }
        }

    @JvmStatic
    fun loadMediaViewerFragmentClass(loader: ClassLoader): Class<*>? =
        UnobfuscatorCache.getInstance().getClassOrNull(loader) {
            resolve(
                "mediaViewerFragment",
                listOf("com.instagram.feed.media.MediaViewerFragment", "MediaViewerFragment", "MediaViewerPageFragment"),
                StringMatchType.Contains
            ) { anchor -> findFirstClassUsingStrings(loader, StringMatchType.Contains, anchor) }
        }

    @JvmStatic
    fun loadReelViewerFragmentClass(loader: ClassLoader): Class<*>? =
        UnobfuscatorCache.getInstance().getClassOrNull(loader) {
            resolve(
                "reelViewerFragment",
                listOf("ReelMediaViewerFragment", "ReelViewerFragment", "com.instagram.reels.viewer.ReelViewerFragment"),
                StringMatchType.Contains
            ) { anchor -> findFirstClassUsingStrings(loader, StringMatchType.Contains, anchor) }
        }

    // ---------------------------------------------------------------------------------------
    // Tabs / profile grid
    // ---------------------------------------------------------------------------------------

    /**
     * Bottom-nav tab registry. IG builds a `List<Tab>` from ordered `TabMetadata` objects and
     * hands it to the nav; hooking the builder is the only version-stable way to drop a tab
     * without breaking the nav's index bookkeeping.
     */
    @JvmStatic
    fun loadMainTabListMethod(loader: ClassLoader): Method? =
        UnobfuscatorCache.getInstance().getMethodOrNull(loader) {
            resolve(
                "mainTabList",
                listOf("MainTab", "MainTabHostView", "tab_config"),
                StringMatchType.Contains
            ) { anchor -> findFirstMethodUsingStrings(loader, StringMatchType.Contains, anchor) }
        }

    @JvmStatic
    fun loadMainTabListClass(loader: ClassLoader): Class<*>? =
        UnobfuscatorCache.getInstance().getClassOrNull(loader) {
            resolve(
                "mainTabListClass",
                listOf("com.instagram.maintab.MainTab", "MainTab"),
                StringMatchType.Contains
            ) { anchor -> findFirstClassUsingStrings(loader, StringMatchType.Contains, anchor) }
        }

    @JvmStatic
    fun loadProfileTabLayoutManager(loader: ClassLoader, columnsResourceId: Int): Method? {
        if (columnsResourceId <= 0) return null
        return UnobfuscatorCache.getInstance().getMethodOrNull(loader) {
            bridge.findMethod {
                matcher {
                    addUsingNumber(columnsResourceId)
                    returnType("androidx.recyclerview.widget.GridLayoutManager")
                }
            }.firstOrNull()?.getMethodInstance(loader)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Diagnostics
    // ---------------------------------------------------------------------------------------

    /**
     * Instagram's own staleness check. Returns `null` on builds where the check moved or was
     * removed, which is the common case on recent betas - `disableExpirationVersion` treats
     * that as "nothing to do".
     */
    @JvmStatic
    fun loadExpirationClass(loader: ClassLoader): Class<*>? {
        val anchors = listOf(
            "com.instagram.utility.LaunchTimeHelper",
            "IgSdkVersion",
            "com.instagram.utility.ProcessUtil"
        )
        return resolve("expiration", anchors, StringMatchType.Contains) { anchor ->
            findFirstClassUsingName(loader, StringMatchType.EndsWith, anchor)
                ?: findFirstClassUsingStrings(loader, StringMatchType.Contains, anchor)
        }
    }

    /**
     * Finds the view class that inflates a specific layout, by looking for a class with a method
     * that references the layout's resource id. This resolves view classes even when their own
     * names are fully obfuscated, which is the normal case on Instagram.
     */
    @JvmStatic
    fun findClassUsingResourceId(loader: ClassLoader, layoutId: Int): Class<*>? {
        if (layoutId <= 0) return null
        return bridge.findClass {
            matcher {
                addMethod { addUsingNumber(layoutId) }
            }
        }.asSequence()
            .mapNotNull { convertRealClass(it, loader) }
            .firstOrNull { it.name.startsWith("android.") }
            ?: bridge.findClass {
                matcher { addMethod { addUsingNumber(layoutId) } }
            }.asSequence()
                .mapNotNull { convertRealClass(it, loader) }
                .firstOrNull()
    }

    /**
     * Enumerates every class/method matching a free-form string. Used by [DebugFeature] to
     * re-pin anchors on a new Instagram build without shipping a release.
     */
    @JvmStatic
    fun probe(classLoader: ClassLoader, needle: String): String {
        val methods = bridge.findMethod {
            matcher { addUsingString(needle, StringMatchType.Contains) }
        }
        val classes = bridge.findClass {
            matcher { addUsingString(needle, StringMatchType.Contains) }
        }
        val sb = StringBuilder()
        sb.appendLine("probe(\"$needle\") -> ${methods.size} methods, ${classes.size} classes")
        methods.take(25).forEach {
            sb.appendLine("  M ${it.className}->${it.name} ${it.paramTypeNames}")
        }
        classes.take(25).forEach {
            sb.appendLine("  C ${it.name}")
        }
        if (methods.size > 25 || classes.size > 25) sb.appendLine("  ... truncated")
        return sb.toString()
    }

    @JvmStatic
    fun resolveClass(name: String, loader: ClassLoader): Class<*>? =
        XposedHelpers.findClassIfExists(name, loader)

    @JvmStatic
    fun methodsOf(clazz: Class<*>): Array<Method> = clazz.declaredMethods

    @JvmStatic
    fun isViewGroup(clazz: Class<*>): Boolean = ViewGroup::class.java.isAssignableFrom(clazz)

    @JvmStatic
    fun isView(clazz: Class<*>): Boolean = View::class.java.isAssignableFrom(clazz)

    @JvmStatic
    @Suppress("unused")
    fun unusedDescriptorGuard(method: Method): String =
        method.declaringClass.name + "->" + method.name +
                method.parameterTypes.joinToString(",") { it.name }
}
