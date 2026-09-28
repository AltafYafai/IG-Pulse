package com.igpulse.xposed.utils

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.content.res.XResources
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RoundRectShape
import android.os.Build
import androidx.core.content.ContextCompat
import com.igpulse.IGPulse
import de.robv.android.xposed.XposedBridge

/**
 * Colour and drawable helpers for the theme engine.
 *
 * Instagram's palette is read through the resource table, so overrides are installed as
 * `XResources` replacements rather than by patching Instagram's theme attributes. That keeps
 * Instagram's own light/dark switching intact - only the resolved colour value changes.
 */
object DesignUtils {

    private var mPrefs: SharedPreferences? = null

    data class Palette(val primary: Int, val background: Int, val text: Int) {
        fun describe(): String = "primary=#%06X background=#%06X text=#%06X"
            .format(primary and 0xFFFFFF, background and 0xFFFFFF, text and 0xFFFFFF)
    }

    private val PRESETS = mapOf(
        "green" to 0xFF2E6C31.toInt(),
        "blue" to 0xFF1D61D2.toInt(),
        "cyan" to 0xFF00687A.toInt(),
        "purple" to 0xFF6750A4.toInt(),
        "orange" to 0xFFB3541E.toInt(),
        "red" to 0xFFB3261E.toInt(),
        "pink" to 0xFFD81B60.toInt()
    )

    /**
     * Resolves the colour set to apply. Returns `null` when neither the preset nor the manual
     * values are usable, which is the signal for the feature to skip itself.
     */
    @JvmStatic
    fun palette(prefs: SharedPreferences): Palette? {
        if (prefs.getBoolean("changecolor", false) != true) return null

        val night = isNightMode()
        val primary = when (prefs.getString("changecolor_mode", "manual")) {
            "monet" -> monetColor(if (night) "system_accent1_300" else "system_accent1_600")
            else -> prefs.getInt("primary_color", 0).takeIf { it != 0 }
                ?: PRESETS[prefs.getString("color_preset", "purple")]
        } ?: return null

        val background = prefs.getInt("background_color", 0).takeIf { it != 0 }
            ?: monetColor(if (night) "system_neutral1_900" else "system_neutral1_10")
            ?: if (night) 0xFF121212.toInt() else 0xFFFFFFFF.toInt()

        val text = prefs.getInt("text_color", 0).takeIf { it != 0 }
            ?: if (night) 0xFFEDEDED.toInt() else 0xFF1C1C1E.toInt()

        return Palette(primary, background, text)
    }

    /**
     * Installs a colour override for the Instagram process. Returns false when the resource
     * hook is not available, e.g. because the app was not launched through the module.
     */
    @JvmStatic
    fun setReplacementColor(name: String, color: Int, night: Boolean): Boolean {
        val res = IGPulse.resParam?.res ?: return false
        return runCatching {
            res.setReplacement(
                Utils.application.packageName, "color", name, color
            )
            true
        }.onFailure { XposedBridge.log(it) }.getOrDefault(false)
    }

    @JvmStatic
    fun setReplacementDrawable(name: String, replacement: Drawable?) {
        val res = IGPulse.resParam?.res ?: return
        runCatching {
            res.setReplacement(
                Utils.application.packageName, "drawable", name,
                object : XResources.DrawableLoader() {
                    override fun newDrawable(res: XResources, id: Int): Drawable = replacement!!
                }
            )
        }
    }

    @JvmStatic
    fun isNightMode(): Boolean =
        (Utils.application.resources.configuration.uiMode and 48) == 32

    @JvmStatic
    fun setPrefs(prefs: SharedPreferences) {
        mPrefs = prefs
    }

    @JvmStatic
    fun isValidColor(color: String?): Boolean = runCatching {
        Color.parseColor(color)
        true
    }.getOrDefault(false)

    @JvmStatic
    fun checkSystemColor(color: String?): String {
        if (isValidColor(color)) return color!!
        if (color != null && color.startsWith("color_")) {
            val resName = color.removePrefix("color_")
            val colorRes = runCatching {
                android.R.color::class.java.getField(resName).getInt(null)
            }.getOrDefault(-1)
            if (colorRes != -1) {
                return "#%06X".format(
                    ContextCompat.getColor(Utils.application, colorRes) and 0xFFFFFF
                )
            }
        }
        return "0"
    }

    private fun monetColor(resourceName: String): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val parsed = checkSystemColor("color_$resourceName")
        return if (isValidColor(parsed)) runCatching { Color.parseColor(parsed) }.getOrNull() else null
    }

    // ---------------------------------------------------------------------------------------
    // Drawable helpers (used by the tab and viewer overrides)
    // ---------------------------------------------------------------------------------------

    @SuppressLint("UseCompatLoadingForDrawables")
    @JvmStatic
    fun getDrawable(id: Int): Drawable? = Utils.application.getDrawable(id)

    @JvmStatic
    fun getDrawableByName(name: String): Drawable? =
        Utils.getID(name, "drawable").takeIf { it > 0 }?.let { getDrawable(it) }

    @JvmStatic
    fun getIconByName(name: String, isTheme: Boolean): Drawable? {
        val icon = getDrawableByName(name) ?: return null
        return if (isTheme) coloredDrawable(icon, if (isNightMode()) Color.WHITE else Color.BLACK)
        else icon
    }

    @JvmStatic
    fun coloredDrawable(drawable: Drawable, color: Int): Drawable {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            drawable.colorFilter = BlendModeColorFilter(color, BlendMode.SRC_ATOP)
        } else {
            @Suppress("DEPRECATION")
            drawable.setColorFilter(color, PorterDuff.Mode.SRC_ATOP)
        }
        return drawable
    }

    @JvmStatic
    fun createDrawable(type: String, color: Int): Drawable = when (type) {
        "rc_dialog_bg" -> roundRect(Utils.dipToPixels(12f).toFloat(), color)
        "selector_bg" -> roundRect(Utils.dipToPixels(18f).toFloat(), color)
        "rc_dotline_dialog" -> roundRect(Utils.dipToPixels(16f).toFloat(), color)
        "stroke_border" -> {
            val shape = roundRect(Utils.dipToPixels(18f).toFloat(), Color.TRANSPARENT)
            val paint = shape.paint
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = Utils.dipToPixels(2).toFloat()
            paint.color = color
            val inset = Utils.dipToPixels(2)
            InsetDrawable(shape, inset, inset, inset, inset)
        }
        else -> ColorDrawable(Color.BLACK)
    }

    private fun roundRect(radius: Float, color: Int): ShapeDrawable =
        ShapeDrawable(RoundRectShape(floatArrayOf(radius, radius, radius, radius, radius, radius, radius, radius), null, null))
            .apply { paint.color = color }

    @JvmStatic
    fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) return drawable.bitmap
        val bitmap = Bitmap.createBitmap(
            drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888
        )
        Canvas(bitmap).also { drawable.setBounds(0, 0, it.width, it.height) }
            .also { drawable.draw(it) }
        return bitmap
    }

    @JvmStatic
    fun getDominantColor(bitmap: Bitmap): Int {
        val counts = HashMap<Int, Int>()
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val color = bitmap.getPixel(x, y)
                if (Color.alpha(color) > 0) counts[color] = counts.getOrDefault(color, 0) + 1
            }
        }
        return counts.entries.maxByOrNull { it.value }?.key ?: Color.BLACK
    }

    @JvmStatic
    fun replaceColor(bitmap: Bitmap, oldColor: Int, newColor: Int, threshold: Double): Bitmap {
        val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        for (y in 0 until out.height) {
            for (x in 0 until out.width) {
                if (colorDistance(out.getPixel(x, y), oldColor) < threshold) {
                    out.setPixel(x, y, newColor)
                }
            }
        }
        return out
    }

    private fun colorDistance(c1: Int, c2: Int): Double {
        val dr = Color.red(c1) - Color.red(c2)
        val dg = Color.green(c1) - Color.green(c2)
        val db = Color.blue(c1) - Color.blue(c2)
        return kotlin.math.sqrt((dr * dr + dg * dg + db * db).toDouble())
    }

    @JvmStatic
    fun resizeDrawable(icon: Drawable, width: Int, height: Int): Drawable {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).also { icon.setBounds(0, 0, it.width, it.height) }.also { icon.draw(it) }
        return BitmapDrawable(Utils.application.resources, bitmap)
    }

    @Suppress("unused")
    private fun mPrefs(): SharedPreferences? = mPrefs
}
