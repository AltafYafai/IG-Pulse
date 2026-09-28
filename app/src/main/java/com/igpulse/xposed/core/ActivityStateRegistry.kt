package com.igpulse.xposed.core

import android.app.Activity
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/**
 * Tracks live Instagram activities so features that need a window (download prompts, restart
 * dialogs, error surfaces) can reach one without holding a strong reference to it.
 */
object ActivityStateRegistry {

    enum class ChangeType { CREATED, STARTED, RESUMED, PAUSED, STOPPED, DESTROYED }

    private val activityStates: MutableMap<Activity, ChangeType> =
        Collections.synchronizedMap(WeakHashMap<Activity, ChangeType>())

    private val activityBySimpleName: MutableMap<String, WeakReference<Activity>> =
        Collections.synchronizedMap(HashMap<String, WeakReference<Activity>>())

    @Volatile
    private var resumed: WeakReference<Activity>? = null

    val lastResumed: Activity?
        get() = resumed?.get()

    @JvmStatic
    fun updateState(activity: Activity?, type: ChangeType?) {
        if (activity == null || type == null) return
        activityStates[activity] = type
        activityBySimpleName[activity.javaClass.simpleName] = WeakReference(activity)
        if (type == ChangeType.RESUMED) {
            resumed = WeakReference(activity)
            listeners.forEach { runCatching { it(activity, type) } }
        } else if (type == ChangeType.DESTROYED && resumed?.get() === activity) {
            resumed = null
        }
    }

    @JvmStatic
    fun findBySimpleName(name: String): Activity? = activityBySimpleName[name]?.get()

    @JvmStatic
    fun cleanup() {
        activityBySimpleName.entries.removeAll { it.value.get() == null }
    }

    private val listeners = mutableListOf<(Activity, ChangeType) -> Unit>()

    @JvmStatic
    @Synchronized
    fun addListener(block: (Activity, ChangeType) -> Unit) {
        listeners.add(block)
    }

    @JvmStatic
    @Synchronized
    fun removeListener(block: (Activity, ChangeType) -> Unit) {
        listeners.remove(block)
    }
}
