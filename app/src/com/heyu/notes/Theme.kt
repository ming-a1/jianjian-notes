package com.heyu.notes

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.WindowInsetsController

/** 配色与主题（浅色 / 深色 / 跟随系统，默认浅色） */
data class Palette(
    val bg: Int,
    val bg2: Int,
    val panel: Int,
    val text: Int,
    val text2: Int,
    val text3: Int,
    val border: Int,
    val accent: Int,
    val accentSoft: Int,
    val codeBg: Int,
    val danger: Int,
    val dark: Boolean
)

object Theme {

    private val LIGHT = Palette(
        bg = 0xFFFFFFFF.toInt(), bg2 = 0xFFF7F8FA.toInt(), panel = 0xFFFFFFFF.toInt(),
        text = 0xFF15171C.toInt(), text2 = 0xFF5B6270.toInt(), text3 = 0xFF8B93A1.toInt(),
        border = 0xFFE7E9EE.toInt(), accent = 0xFF4F46E5.toInt(), accentSoft = 0xFFEEF0FF.toInt(),
        codeBg = 0xFFF6F7F9.toInt(), danger = 0xFFE5484D.toInt(), dark = false
    )
    private val DARK = Palette(
        bg = 0xFF101216.toInt(), bg2 = 0xFF15181E.toInt(), panel = 0xFF161A21.toInt(),
        text = 0xFFE8EAF0.toInt(), text2 = 0xFF9AA2B1.toInt(), text3 = 0xFF6F7787.toInt(),
        border = 0xFF252A34.toInt(), accent = 0xFF8B87FF.toInt(), accentSoft = 0xFF23233D.toInt(),
        codeBg = 0xFF1A1E26.toInt(), danger = 0xFFFF6B6B.toInt(), dark = true
    )

    fun mode(ctx: Context): String {
        val s = AppSettings.load(ctx)
        return Json.str(s, "theme", "light")
    }

    fun isSystemDark(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun isDark(ctx: Context): Boolean = when (mode(ctx)) {
        "dark" -> true
        "system" -> isSystemDark(ctx)
        else -> false
    }

    fun palette(ctx: Context): Palette = if (isDark(ctx)) DARK else LIGHT

    fun setMode(ctx: Context, mode: String) {
        val s = AppSettings.load(ctx)
        s["theme"] = mode
        AppSettings.save(ctx, s)
    }

    fun fontSize(ctx: Context): Int {
        val s = AppSettings.load(ctx)
        return Json.num(s, "fontSize", 16).coerceIn(13, 22)
    }

    fun setFontSize(ctx: Context, size: Int) {
        val s = AppSettings.load(ctx)
        s["fontSize"] = size
        AppSettings.save(ctx, s)
    }

    fun surface(ctx: Context, p: Palette): Int = p.bg

    fun applyWindow(activity: Activity, p: Palette) {
        val window = activity.window
        window.statusBarColor = p.bg
        window.navigationBarColor = p.bg
        val decor: View = window.decorView
        if (Build.VERSION.SDK_INT >= 30) {
            val c: WindowInsetsController? = decor.windowInsetsController
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            c?.setSystemBarsAppearance(if (p.dark) 0 else mask, mask)
        } else {
            var flags = decor.systemUiVisibility
            flags = if (p.dark) flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            else flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            decor.systemUiVisibility = flags
        }
    }

    fun alpha(color: Int, a: Float): Int {
        val al = (Color.alpha(color) * a).toInt().coerceIn(0, 255)
        return Color.argb(al, Color.red(color), Color.green(color), Color.blue(color))
    }
}
