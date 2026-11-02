package com.heyu.notes

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.Switch
import android.widget.TextView

/** 原生控件小工具：统一间距、圆角、颜色 */
object Ui {

    fun dp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    fun sp(ctx: Context, v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, ctx.resources.displayMetrics)

    fun rounded(color: Int, radiusDp: Int, ctx: Context, strokeColor: Int? = null, strokeDp: Int = 0): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = dp(ctx, radiusDp.toFloat()).toFloat()
        if (strokeColor != null) d.setStroke(dp(ctx, strokeDp.toFloat()), strokeColor)
        return d
    }

    fun ripple(base: GradientDrawable, rippleColor: Int): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(rippleColor), base, null)

    fun textView(ctx: Context, text: CharSequence = "", size: Float = 15f, color: Int = Color.BLACK): TextView {
        val tv = TextView(ctx)
        tv.text = text
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        tv.setTextColor(color)
        tv.setLineSpacing(0f, 1.35f)
        return tv
    }

    fun title(ctx: Context, t: String, p: Palette, size: Float = 17f): TextView {
        val tv = textView(ctx, t, size, p.text)
        tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        return tv
    }

    fun hint(ctx: Context, t: String, p: Palette, size: Float = 12f): TextView {
        val tv = textView(ctx, t, size, p.text3)
        return tv
    }

    fun button(ctx: Context, label: String, primary: Boolean, p: Palette, small: Boolean = false): TextView {
        val tv = TextView(ctx)
        tv.text = label
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) 12.5f else 14f)
        tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        tv.gravity = Gravity.CENTER
        tv.includeFontPadding = false
        val h = if (small) 34 else 46
        tv.setPadding(dp(ctx, 14f), 0, dp(ctx, 14f), 0)
        tv.minWidth = dp(ctx, if (small) 58f else 76f)
        tv.minHeight = dp(ctx, h.toFloat())
        tv.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, h.toFloat())
        )
        if (primary) {
            tv.setTextColor(if (p.dark) 0xFF0D0F14.toInt() else Color.WHITE)
            tv.background = ripple(rounded(p.accent, if (small) 8 else 10, ctx), Theme.alpha(p.accent, 0.18f))
        } else {
            tv.setTextColor(p.text)
            tv.background = ripple(rounded(p.bg2, if (small) 8 else 10, ctx, p.border, 1),
                Theme.alpha(p.text, 0.06f))
        }
        tv.isClickable = true
        tv.isFocusable = true
        return tv
    }

    fun dangerButton(ctx: Context, label: String, p: Palette, small: Boolean = true): TextView {
        val tv = button(ctx, label, false, p, small)
        tv.setTextColor(p.danger)
        tv.background = ripple(rounded(p.bg2, 8, ctx, Theme.alpha(p.danger, 0.35f), 1), Theme.alpha(p.danger, 0.08f))
        return tv
    }

    fun iconButton(ctx: Context, res: Int, tint: Int, sizeDp: Int = 40, iconDp: Int = 20): ImageButton {
        val b = ImageButton(ctx)
        b.setImageResource(res)
        b.scaleType = ImageView.ScaleType.CENTER_INSIDE
        b.setColorFilter(tint)
        b.setPadding(dp(ctx, (sizeDp - iconDp) / 2f), dp(ctx, (sizeDp - iconDp) / 2f),
            dp(ctx, (sizeDp - iconDp) / 2f), dp(ctx, (sizeDp - iconDp) / 2f))
        b.background = ripple(rounded(Color.TRANSPARENT, 10, ctx), Theme.alpha(tint, 0.08f))
        b.layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp.toFloat()), dp(ctx, sizeDp.toFloat()))
        return b
    }

    fun switchView(ctx: Context, checked: Boolean, p: Palette): Switch {
        val sw = Switch(ctx)
        sw.isChecked = checked
        sw.thumbTintList = ColorStateList.valueOf(p.accent)
        sw.trackTintList = ColorStateList.valueOf(Theme.alpha(p.accent, 0.35f))
        sw.buttonTintList = ColorStateList.valueOf(p.accent)
        return sw
    }

    fun editText(ctx: Context, hint: String, p: Palette, value: String = "", singleLine: Boolean = true, inputType: Int = InputType.TYPE_CLASS_TEXT): EditText {
        val et = EditText(ctx)
        et.hint = hint
        et.setText(value)
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        et.setTextColor(p.text)
        et.setHintTextColor(p.text3)
        et.setSingleLine(singleLine)
        et.inputType = inputType
        et.setPadding(dp(ctx, 12f), dp(ctx, 9f), dp(ctx, 12f), dp(ctx, 9f))
        et.background = rounded(p.bg, 9, ctx, p.border, 1)
        et.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return et
    }

    fun fieldLabel(ctx: Context, label: String, p: Palette): TextView {
        val tv = textView(ctx, label, 12f, p.text3)
        tv.setPadding(0, dp(ctx, 10f), 0, dp(ctx, 4f))
        return tv
    }

    fun sectionTitle(ctx: Context, t: String, p: Palette): TextView {
        val tv = textView(ctx, t, 12.5f, p.text2)
        tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        tv.letterSpacing = 0.06f
        tv.setPadding(0, dp(ctx, 16f), 0, dp(ctx, 6f))
        return tv
    }

    fun divider(ctx: Context, p: Palette): View {
        val v = View(ctx)
        v.setBackgroundColor(p.border)
        v.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1f))
        return v
    }

    fun space(ctx: Context, h: Int): Space {
        val s = Space(ctx)
        s.layoutParams = LinearLayout.LayoutParams(1, dp(ctx, h.toFloat()))
        return s
    }

    fun row(ctx: Context): LinearLayout {
        val l = LinearLayout(ctx)
        l.orientation = LinearLayout.HORIZONTAL
        l.gravity = Gravity.CENTER_VERTICAL
        return l
    }

    fun column(ctx: Context): LinearLayout {
        val l = LinearLayout(ctx)
        l.orientation = LinearLayout.VERTICAL
        return l
    }

    fun badge(ctx: Context, t: String, color: Int, bg: Int): TextView {
        val tv = textView(ctx, t, 10.5f, color)
        tv.setPadding(dp(ctx, 6f), dp(ctx, 2f), dp(ctx, 6f), dp(ctx, 2f))
        tv.background = rounded(bg, 5, ctx)
        return tv
    }
}
