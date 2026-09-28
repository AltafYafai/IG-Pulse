package com.igpulse.xposed.features.utility

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.igpulse.xposed.core.Feature
import com.igpulse.xposed.core.IgCore
import com.igpulse.xposed.core.devkit.Unobfuscator
import com.igpulse.xposed.utils.Utils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Long-press actions on text Instagram already renders.
 *
 * Two surfaces are covered, both resolved the same way - by the view class that the matching
 * layout inflates:
 *
 *  * direct message rows, giving Copy / Translate / Share;
 *  * post and reel captions, giving Copy / Translate.
 *
 * Text is read straight off the view at press time rather than from a model, so what gets
 * copied is exactly what is on screen including any truncation or link rewriting Instagram
 * applied.
 *
 * Translate hands the text to the system rather than calling a translation API, which keeps the
 * module free of network calls and of an API key. [webTranslateUrl] is the fallback when no
 * app claims `ACTION_SEND` for text.
 */
class ContentActions(
    classLoader: ClassLoader,
    prefs: SharedPreferences) : Feature(classLoader, prefs) {

    override fun doHook() {
        var hooked = 0

        if (prefs.getBoolean("copy_selection", false) || prefs.getBoolean("google_translate", false)) {
            val messageRow = resolveLayoutClass("direct_message_text_view", "row_message_text_view")
            if (messageRow != null) {
                attach(messageRow, includeShare = true)
                hooked++
            } else {
                log("direct message row view unresolved")
            }

            val caption = resolveLayoutClass("media_caption", "caption_text_view")
            if (caption != null) {
                attach(caption, includeShare = false)
                hooked++
            } else {
                log("caption view unresolved")
            }
        }

        if (hooked == 0) log("nothing to attach to")
    }

    /**
     * Resolves the view class that inflates a layout by looking for a class whose methods
     * reference the layout's own resource id. This works even when the view class name itself
     * is fully obfuscated, which is the normal case.
     */
    private fun resolveLayoutClass(vararg names: String): Class<*>? {
        for (name in names) {
            val layoutId = Utils.getID(name, "layout")
            if (layoutId <= 0) continue
            val found = Unobfuscator.findClassUsingResourceId(classLoader, layoutId)
            if (found != null) return found
            log("layout '$name' (id=$layoutId) matched no class")
        }
        return null
    }

    private fun attach(viewClass: Class<*>, includeShare: Boolean) {
        val bind = viewClass.declaredMethods.firstOrNull { method ->
            method.parameterCount == 1 && method.returnType == Void.TYPE
        } ?: run {
            log("no bind method on ${viewClass.name}")
            return
        }

        XposedBridge.hookMethod(bind, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                view.setOnLongClickListener { onLongPress(view, includeShare) }
            }
        })
        log("hooked ${Unobfuscator.getMethodDescriptor(bind)} on ${viewClass.name}")
    }

    private fun onLongPress(view: View, includeShare: Boolean): Boolean {
        if (view !is TextView) return false
        val text = view.text?.toString()?.takeIf { it.isNotBlank() } ?: return false
        val activity = IgCore.lastResumed ?: return false

        activity.runOnUiThread {
            val actions = buildList {
                if (prefs.getBoolean("copy_selection", false)) add(getString(com.igpulse.R.string.copy_to_clipboard))
                if (prefs.getBoolean("google_translate", false)) add(getString(com.igpulse.R.string.google_translate))
                if (includeShare) add(getString(com.igpulse.R.string.share))
            }
            if (actions.isEmpty()) return@runOnUiThread

            MaterialAlertDialogBuilder(activity)
                .setItems(actions.toTypedArray()) { _, which ->
                    when (actions[which]) {
                        getString(com.igpulse.R.string.copy_to_clipboard) -> copy(text)
                        getString(com.igpulse.R.string.google_translate) -> translate(activity, text)
                        else -> share(activity, text)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        return true
    }

    private fun copy(text: String) {
        val context = Utils.application
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("igpulse", text))
        Utils.showToast(getString(com.igpulse.R.string.copied_to_clipboard), Toast.LENGTH_SHORT)
    }

    private fun translate(activity: android.app.Activity, text: String) {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        if (share.resolveActivity(activity.packageManager) != null) {
            activity.startActivity(Intent.createChooser(share, getString(com.igpulse.R.string.google_translate)))
        } else {
            Utils.openLink(activity, webTranslateUrl(text, "en"))
        }
    }

    private fun share(activity: android.app.Activity, text: String) {
        activity.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                },
                getString(com.igpulse.R.string.share)
            )
        )
    }

    private fun getString(id: Int): String = Utils.getString(id)

    override fun getPluginName(): String = "ContentActions"

    companion object {
        @JvmStatic
        fun webTranslateUrl(text: String, target: String): String =
            "https://translate.google.com/?sl=auto" +
                    "&tl=" + URLEncoder.encode(target, StandardCharsets.UTF_8.name()) +
                    "&text=" + URLEncoder.encode(text, StandardCharsets.UTF_8.name()) +
                    "&op=translate"
    }
}
