package com.heyu.notes

import android.app.Activity
import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 自绘开关（替代系统 Switch） */
class Toggle @JvmOverloads constructor(
    ctx: Context,
    private val pal: Palette,
    checked: Boolean = false
) : View(ctx) {

    private var on = checked
    private var pos = if (checked) 1f else 0f
    private var animator: android.animation.ValueAnimator? = null
    var listener: ((Boolean) -> Unit)? = null

    init {
        layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, 46f), Ui.dp(ctx, 26f))
        isClickable = true
        super.setOnClickListener { setOn(!on, true) }
    }

    fun isOn(): Boolean = on

    fun setOn(on: Boolean, notify: Boolean) {
        if (this.on == on) return
        this.on = on
        animator?.cancel()
        animator = android.animation.ValueAnimator.ofFloat(pos, if (on) 1f else 0f).apply {
            duration = 160
            interpolator = DecelerateInterpolator()
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            start()
        }
        if (notify) listener?.invoke(on)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(Ui.dp(context, 46f), Ui.dp(context, 26f))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val track = Paint(Paint.ANTI_ALIAS_FLAG)
        track.color = if (on) pal.accent else Theme.alpha(pal.text3, 0.35f)
        canvas.drawRoundRect(RectF(0f, 0f, w, h), h / 2f, h / 2f, track)
        val r = h / 2f - Ui.dp(context, 2.5f)
        val cx = Ui.dp(context, 2.5f) + r + (w - h) * pos
        val thumb = Paint(Paint.ANTI_ALIAS_FLAG)
        thumb.color = Color.WHITE
        thumb.setShadowLayer(2f, 0f, 1f, 0x33000000)
        canvas.drawCircle(cx, h / 2f, r, thumb)
    }

}

/** 自定义输入框：标签 + 圆角容器，聚焦时描边变主题色 */
class Field @JvmOverloads constructor(
    ctx: Context,
    private val pal: Palette,
    label: String,
    value: String = "",
    hint: String = "",
    inputType: Int = InputType.TYPE_CLASS_TEXT,
    singleLine: Boolean = true
) : LinearLayout(ctx) {

    val edit: EditText
    private val box: FrameLayout

    init {
        orientation = VERTICAL
        if (label.isNotEmpty()) {
            addView(Ui.textView(ctx, label, 12f, pal.text3).apply {
                setPadding(0, 0, 0, Ui.dp(ctx, 5f))
            })
        }
        box = FrameLayout(ctx)
        box.background = Ui.rounded(pal.bg, 10, ctx, pal.border, 1)
        edit = EditText(ctx)
        edit.setText(value)
        edit.hint = hint
        edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        edit.setTextColor(pal.text)
        edit.setHintTextColor(pal.text3)
        edit.setSingleLine(singleLine)
        edit.inputType = inputType
        edit.background = null
        edit.setPadding(Ui.dp(ctx, 12f), Ui.dp(ctx, 12f), Ui.dp(ctx, 12f), Ui.dp(ctx, 12f))
        edit.includeFontPadding = false
        edit.setOnFocusChangeListener { _, hasFocus ->
            box.background = Ui.rounded(pal.bg, 10, ctx,
                if (hasFocus) pal.accent else pal.border, if (hasFocus) 2 else 1)
        }
        box.addView(edit, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 46f)))
        addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun text(): String = edit.text.toString()
    fun setText(s: String) { edit.setText(s) }
    fun onFocusLost(action: () -> Unit) {
        edit.setOnFocusChangeListener { _, hasFocus ->
            box.background = Ui.rounded(pal.bg, 10, context,
                if (hasFocus) pal.accent else pal.border, if (hasFocus) 2 else 1)
            if (!hasFocus) action()
        }
    }
}

/** 自定义下拉选择（替代系统 Spinner） */
class Select @JvmOverloads constructor(
    ctx: Context,
    private val pal: Palette,
    val options: List<String>,
    selected: Int = 0
) : LinearLayout(ctx) {

    var index: Int = selected
    var onSelect: ((Int) -> Unit)? = null
    private val label: TextView
    private var popup: PopupWindow? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Ui.rounded(pal.bg, 10, ctx, pal.border, 1)
        setPadding(Ui.dp(ctx, 12f), 0, Ui.dp(ctx, 10f), 0)
        label = Ui.textView(ctx, options.getOrElse(selected) { "" }, 14f, pal.text)
        addView(label, LinearLayout.LayoutParams(0, Ui.dp(ctx, 46f), 1f))
        label.gravity = Gravity.CENTER_VERTICAL
        addView(Ui.textView(ctx, "▾", 13f, pal.text3))
        isClickable = true
        setOnClickListener { showPopup() }
    }

    private fun showPopup() {
        val list = LinearLayout(context).apply {
            orientation = VERTICAL
            background = Ui.rounded(pal.panel, 12, context, pal.border, 1)
            setPadding(Ui.dp(context, 6f), Ui.dp(context, 6f), Ui.dp(context, 6f), Ui.dp(context, 6f))
        }
        options.forEachIndexed { i, opt ->
            val tv = Ui.textView(context, opt, 14f, if (i == index) pal.accent else pal.text)
            tv.setPadding(Ui.dp(context, 12f), Ui.dp(context, 11f), Ui.dp(context, 12f), Ui.dp(context, 11f))
            tv.background = Ui.ripple(Ui.rounded(Color.TRANSPARENT, 8, context), Theme.alpha(pal.text, 0.06f))
            tv.isClickable = true
            tv.setOnClickListener {
                index = i
                label.text = options[i]
                popup?.dismiss()
                onSelect?.invoke(i)
            }
            list.addView(tv)
        }
        val pw = PopupWindow(list, width, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        pw.isOutsideTouchable = true
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pw.elevation = Ui.dp(context, 8f).toFloat()
        popup = pw
        pw.showAsDropDown(this, 0, Ui.dp(context, 4f))
    }
}

/** 自定义弹窗（替代 AlertDialog） */
object DialogCard {

    data class Action(
        val label: String,
        val primary: Boolean = false,
        val danger: Boolean = false,
        val dismiss: Boolean = true,
        val onClick: () -> Unit
    )

    fun show(act: Activity, pal: Palette, title: String?, content: View, actions: List<Action>, cancelable: Boolean = true): Dialog {
        val d = Dialog(act)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setWindowAnimations(0)
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(pal.panel, 18, act)
            setPadding(Ui.dp(act, 20f), Ui.dp(act, 18f), Ui.dp(act, 20f), Ui.dp(act, 14f))
        }
        if (title != null) {
            val tv = Ui.textView(act, title, 16f, pal.text)
            tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            tv.setPadding(0, 0, 0, Ui.dp(act, 10f))
            root.addView(tv)
        }
        val scroll = ScrollView(act)
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(act, 14f), 0, 0)
        }
        actions.forEachIndexed { i, a ->
            val btn = if (a.danger) Ui.dangerButton(act, a.label, pal, true)
            else Ui.button(act, a.label, a.primary, pal, true)
            btn.setOnClickListener { if (a.dismiss) d.dismiss(); a.onClick() }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(act, 38f))
            if (i > 0) lp.leftMargin = Ui.dp(act, 8f)
            row.addView(btn, lp)
        }
        root.addView(row)
        d.setContentView(root)
        d.setCancelable(cancelable)
        d.setCanceledOnTouchOutside(cancelable)
        d.show()
        val dm = act.resources.displayMetrics
        d.window?.setLayout((dm.widthPixels * 0.88f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        val decor = d.window?.decorView as? ViewGroup
        Overlay.push(decor)
        d.setOnDismissListener { Overlay.remove(decor) }
        return d
    }

    fun message(
        act: Activity, pal: Palette, title: String?, message: String,
        okLabel: String = "确定", cancelLabel: String? = "取消",
        danger: Boolean = false, onOk: () -> Unit = {}
    ): Dialog {
        val content = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.textView(act, message, 13.5f, pal.text2))
        }
        val actions = ArrayList<Action>()
        if (cancelLabel != null) actions.add(Action(cancelLabel, false, false) { })
        actions.add(Action(okLabel, !danger, danger) { onOk() })
        return show(act, pal, title, content, actions)
    }
}

/** 记录当前显示在最上层的窗口（Dialog 的 decorView），提示条画在它里面才不会被弹窗盖住 */
object Overlay {
    private val stack = ArrayList<ViewGroup>()

    fun push(v: ViewGroup?) {
        if (v != null && !stack.contains(v)) stack.add(v)
    }

    fun remove(v: ViewGroup?) {
        stack.remove(v)
    }

    fun top(act: Activity): ViewGroup {
        for (i in stack.size - 1 downTo 0) {
            val v = stack[i]
            if (v.isAttachedToWindow && v.isShown) return v
        }
        return act.window.decorView as ViewGroup
    }
}

/** 自定义轻提示（替代 Toast） */
object Snack {
    fun show(act: Activity, msg: String) {
        try {
            val decor: ViewGroup = Overlay.top(act)
            val pal = Theme.palette(act)
            val tv = Ui.textView(act, msg, 13f, if (pal.dark) Color.parseColor("#0D0F14") else Color.WHITE)
            tv.setPadding(Ui.dp(act, 18f), Ui.dp(act, 11f), Ui.dp(act, 18f), Ui.dp(act, 11f))
            tv.background = Ui.rounded(if (pal.dark) pal.accent else pal.text, 22, act)
            tv.elevation = Ui.dp(act, 6f).toFloat()
            tv.alpha = 0f
            val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.bottomMargin = Ui.dp(act, 26f)
            decor.addView(tv, lp)
            tv.animate().alpha(1f).setDuration(140).start()
            tv.postDelayed({
                tv.animate().alpha(0f).setDuration(180).withEndAction {
                    try { decor.removeView(tv) } catch (e: Exception) { }
                }.start()
            }, 1900)
        } catch (e: Exception) {
            // 忽略
        }
    }
}

/** 自定义下拉菜单（替代 PopupMenu） */
class MenuPopup(private val act: Activity, private val pal: Palette) {

    data class Item(val label: String, val danger: Boolean = false, val onClick: () -> Unit)

    fun show(anchor: View, items: List<Item>) {
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(pal.panel, 14, act, pal.border, 1)
            setPadding(Ui.dp(act, 6f), Ui.dp(act, 6f), Ui.dp(act, 6f), Ui.dp(act, 6f))
            elevation = Ui.dp(act, 10f).toFloat()
        }
        val pw = PopupWindow(root, Ui.dp(act, 240f), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        items.forEach { item ->
            val tv = Ui.textView(act, item.label, 14f, if (item.danger) pal.danger else pal.text)
            tv.setPadding(Ui.dp(act, 14f), Ui.dp(act, 12f), Ui.dp(act, 14f), Ui.dp(act, 12f))
            tv.background = Ui.ripple(Ui.rounded(Color.TRANSPARENT, 9, act), Theme.alpha(pal.text, 0.06f))
            tv.isClickable = true
            tv.setOnClickListener { pw.dismiss(); item.onClick() }
            root.addView(tv)
        }
        pw.isOutsideTouchable = true
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pw.elevation = Ui.dp(act, 10f).toFloat()
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val dm = act.resources.displayMetrics
        val x = (dm.widthPixels - Ui.dp(act, 240f) - Ui.dp(act, 12f)).coerceAtLeast(Ui.dp(act, 8f))
        val y = loc[1] + anchor.height + Ui.dp(act, 4f)
        pw.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
    }
}

/** 分享预览：先看效果，再选择「分享到平台」或「保存到图库」 */
object SharePreview {

    fun show(act: Activity, pal: Palette, note: Note, bmp: Bitmap) {
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(pal.panel, 18, act)
            setPadding(Ui.dp(act, 14f), Ui.dp(act, 14f), Ui.dp(act, 14f), Ui.dp(act, 12f))
        }
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = Ui.textView(act, "分享预览", 15f, pal.text)
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        head.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close = Ui.textView(act, "✕", 15f, pal.text3)
        close.setPadding(Ui.dp(act, 8f), Ui.dp(act, 4f), 0, Ui.dp(act, 4f))
        close.isClickable = true
        head.addView(close)
        root.addView(head)

        val scroll = ScrollView(act)
        scroll.isFillViewport = false
        val img = ImageView(act)
        img.setImageBitmap(bmp)
        img.adjustViewBounds = true
        img.scaleType = ImageView.ScaleType.FIT_CENTER
        img.setPadding(0, Ui.dp(act, 10f), 0, Ui.dp(act, 10f))
        scroll.addView(img, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, Ui.dp(act, 6f), 0, 0) }
        val save = Ui.button(act, "保存到图库", false, pal)
        val share = Ui.button(act, "分享到平台", true, pal)
        val lp1 = LinearLayout.LayoutParams(0, Ui.dp(act, 44f), 1f)
        lp1.rightMargin = Ui.dp(act, 10f)
        row.addView(save, lp1)
        row.addView(share, LinearLayout.LayoutParams(0, Ui.dp(act, 44f), 1f))
        root.addView(row)

        val d = Dialog(act)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setWindowAnimations(0)
        d.setContentView(root)
        d.show()
        val dm = act.resources.displayMetrics
        d.window?.setLayout((dm.widthPixels * 0.9f).toInt(), (dm.heightPixels * 0.82f).toInt())
        val decor = d.window?.decorView as? ViewGroup
        Overlay.push(decor)
        d.setOnDismissListener { Overlay.remove(decor) }
        close.setOnClickListener { d.dismiss() }

        save.setOnClickListener {
            Thread {
                val msg = saveToGallery(act, bmp)
                act.runOnUiThread { Snack.show(act, msg) }
            }.start()
        }
        share.setOnClickListener {
            d.dismiss()
            shareImage(act, bmp, note)
        }
    }

    private fun fileName(): String =
        "jianjian-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".png"

    fun saveToGallery(act: Activity, bmp: Bitmap): String {
        val name = fileName()
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/简笺")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = act.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return "保存失败"
                resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "已保存到相册：Pictures/简笺"
            } else {
                val dir = File(act.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "简笺")
                if (!dir.exists()) dir.mkdirs()
                val f = File(dir, name)
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                "已保存到：${f.absolutePath}"
            }
        } catch (e: Exception) {
            "保存失败：${e.message}"
        }
    }

    fun shareImage(act: Activity, bmp: Bitmap, note: Note) {
        try {
            val dir = ShareProvider.shareDir(act)
            val name = fileName()
            FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = ShareProvider.uriFor(name)
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = android.content.Intent.createChooser(send, "分享笔记图片")
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            act.startActivity(chooser)
        } catch (e: Exception) {
            Snack.show(act, "分享失败：${e.message}")
        }
    }
}
