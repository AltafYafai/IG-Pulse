package com.igpulse.xposed.utils

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.view.ContextThemeWrapper
import com.igpulse.R
import com.igpulse.xposed.core.IgCore


class ModuleContextWrapper(private val base: Context) :
    ContextThemeWrapper(base, R.style.AppTheme) {

    override fun getApplicationContext(): Context {
        return base.applicationContext ?: base
    }

    override fun getClassLoader(): ClassLoader {
        return ModuleContextWrapper::class.java.classLoader
            ?: super.getClassLoader()
    }

    override fun getResources(): Resources {
        return IgCore.moduleContext.resources
    }

    override fun getAssets(): AssetManager {
        return IgCore.moduleContext.assets
    }
}
