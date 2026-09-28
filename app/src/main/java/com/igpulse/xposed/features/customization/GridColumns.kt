package com.igpulse.xposed.features.customization

import android.content.SharedPreferences
import com.igpulse.xposed.core.Feature
import com.igpulse.xposed.core.devkit.Unobfuscator
import com.igpulse.xposed.utils.Utils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * Overrides the column count of the profile grid.
 *
 * Instagram builds the grid's `GridLayoutManager` with a span count taken from a resource
 * (`grid_view_type`), so the override hooks the constructor rather than trying to find a setter -
 * `GridLayoutManager` only accepts the span count at construction time.
 */
class GridColumns(
    classLoader: ClassLoader,
    prefs: SharedPreferences) : Feature(classLoader, prefs) {

    private val columns: Int
        get() = prefs.getString("grid_columns", "3")?.toIntOrNull() ?: 3

    override fun doHook() {
        if (columns <= 0) return

        val gridResource = Utils.getID("grid_view_type", "integer").takeIf { it > 0 }
            ?: Utils.getID("grid_view_type", "id")
        if (gridResource <= 0) {
            log("grid_view_type resource not found")
            return
        }

        val builder = Unobfuscator.loadProfileTabLayoutManager(classLoader, gridResource)
        if (builder == null) {
            log("grid layout manager builder unresolved, trying direct construction")
            hookDirect(gridResource)
            return
        }

        XposedBridge.hookMethod(builder, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val index = param.args.indexOfFirst { it is Int }
                if (index < 0) return
                param.args[index] = columns
            }
        })
        log("hooked ${Unobfuscator.getMethodDescriptor(builder)} -> $columns columns")
    }

    /** Fallback: find the span count argument by the manager's constructor signature. */
    private fun hookDirect(gridResource: Int) {
        val manager = runCatching {
            de.robv.android.xposed.XposedHelpers.findClass(
                "androidx.recyclerview.widget.GridLayoutManager", classLoader
            )
        }.getOrNull()

        if (manager == null) {
            log("GridLayoutManager class unavailable")
            return
        }

        val ctor = manager.declaredConstructors.firstOrNull { c ->
            c.parameterTypes.any { it == Int::class.javaPrimitiveType } &&
                    c.parameterTypes.any { it.name.contains("Orientation") || it == Int::class.javaPrimitiveType }
        } ?: run {
            log("no usable GridLayoutManager constructor")
            return
        }

        XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val index = param.args.indexOfFirst { it is Int }
                if (index >= 0) param.args[index] = columns
            }
        })
        log("hooked GridLayoutManager constructor -> $columns columns")
    }

    override fun getPluginName(): String = "GridColumns"
}
