package com.igpulse

import android.annotation.SuppressLint
import android.content.ContextWrapper
import android.content.res.XModuleResources
import android.view.Window
import android.view.WindowManager
import androidx.preference.PreferenceManager
import com.igpulse.activities.MainActivity
import com.igpulse.xposed.AntiUpdater
import com.igpulse.xposed.bridge.ScopeHook
import com.igpulse.xposed.core.FeatureLoader
import com.igpulse.xposed.downgrade.Patch
import de.robv.android.xposed.IXposedHookInitPackageResources
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_InitPackageResources.InitPackageResourcesParam
import de.robv.android.xposed.callbacks.XC_LoadPackage

class IGPulse : IXposedHookLoadPackage, IXposedHookInitPackageResources, IXposedHookZygoteInit {

    companion object {
        private var pref: XSharedPreferences? = null

        @JvmStatic
        var resParam: InitPackageResourcesParam? = null

        /**
         * Filesystem path of the module APK that LSPosed loaded. Read by `IgCore` as a
         * fallback when the module package is not resolvable from the hooked process
         * (not installed for that user, or hidden by package-visibility rules).
         */
        @JvmStatic
        var modulePath: String? = null
            private set

        @JvmStatic
        fun getPref(): XSharedPreferences {
            return pref ?: XSharedPreferences(
                BuildConfig.APPLICATION_ID,
                BuildConfig.APPLICATION_ID + "_preferences"
            ).apply {
                makeWorldReadable()
                reload()
                pref = this
            }
        }
    }

    @Throws(Throwable::class)
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName
        val classLoader = lpparam.classLoader

        // Self-scope: the settings app fakes its own "module active" state so the UI can
        // reflect hook status, and its preferences stay world-readable for the hooked process.
        if (packageName == BuildConfig.APPLICATION_ID) {
            XposedHelpers.findAndHookMethod(
                MainActivity::class.java.name,
                classLoader,
                "isXposedEnabled",
                XC_MethodReplacement.returnConstant(true)
            )

            @Suppress("DEPRECATION")
            @SuppressLint("WorldReadableFiles")
            XposedHelpers.findAndHookMethod(
                PreferenceManager::class.java.name,
                classLoader,
                "getDefaultSharedPreferencesMode",
                XC_MethodReplacement.returnConstant(ContextWrapper.MODE_WORLD_READABLE)
            )

            XposedHelpers.findAndHookMethod(
                "android.app.ContextImpl", classLoader, "checkMode", Int::class.javaPrimitiveType!!,
                XC_MethodReplacement.DO_NOTHING
            )
            return
        }

        AntiUpdater.hookSession(lpparam)
        Patch.handleLoadPackage(lpparam)
        ScopeHook.hook(lpparam)

        if (packageName == FeatureLoader.PACKAGE_IG && lpparam.isFirstApplication) {
            XposedBridge.log("[IG-Pulse] Hooking ${lpparam.packageName}")
            FeatureLoader.start(classLoader, lpparam.appInfo.sourceDir)
            disableSecureFlag()
        }
    }

    @Throws(Throwable::class)
    override fun handleInitPackageResources(resparam: InitPackageResourcesParam) {
        if (resparam.packageName != FeatureLoader.PACKAGE_IG) return

        val modRes = XModuleResources.createInstance(modulePath, resparam.res)
        resParam = resparam
        listOf(
            R.array::class.java,
            R.string::class.java,
            R.drawable::class.java,
            R.layout::class.java,
            R.xml::class.java
        ).forEach { injectResources(it, modRes, resparam) }
    }

    private fun injectResources(
        clazz: Class<*>,
        modRes: XModuleResources?,
        resparam: InitPackageResourcesParam
    ) {
        var count = 0
        for (field in clazz.declaredFields) {
            try {
                field.isAccessible = true
                if (field.type === Int::class.javaPrimitiveType) {
                    val resId = field.getInt(null)
                    if (resId > 0x7f000000) {
                        count++
                        field.set(null, resparam.res.addResource(modRes, resId))
                    }
                } else if (field.type === IntArray::class.java) {
                    val resIds = field.get(null) as IntArray?
                    if (resIds != null) {
                        for (i in resIds.indices) {
                            if (resIds[i] > 0x7f000000) {
                                count++
                                resIds[i] = resparam.res.addResource(modRes, resIds[i])
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        XposedBridge.log("Injected $count resources for ${clazz.simpleName}")
    }

    @Throws(Throwable::class)
    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modulePath = startupParam.modulePath
    }

    /**
     * Instagram sets FLAG_SECURE on story/reel screens. Stripping it is what makes the
     * no-watermark download and screenshot features work at all, so it is applied globally
     * rather than per-window.
     */
    private fun disableSecureFlag() {
        val clear = WindowManager.LayoutParams.FLAG_SECURE.inv()
        XposedHelpers.findAndHookMethod(
            Window::class.java, "setFlags",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.args[0] = (param.args[0] as Int) and clear
                    param.args[1] = (param.args[1] as Int) and clear
                }
            }
        )
        XposedHelpers.findAndHookMethod(
            Window::class.java, "addFlags", Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val newFlags = (param.args[0] as Int) and clear
                    param.args[0] = newFlags
                    if (newFlags == 0) param.result = null
                }
            }
        )
    }
}
