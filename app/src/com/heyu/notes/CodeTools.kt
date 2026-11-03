package com.heyu.notes

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 代码块操作：复制 / 下载 / 分享 / 全屏查看 */
object CodeTools {

    /** 等待用户选择保存位置的内容 */
    @Volatile
    var pendingSave: String? = null

    fun copy(ctx: Context, code: String) {
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("代码", code))
            toast(ctx, "已复制代码")
        } catch (e: Exception) {
            toast(ctx, "复制失败")
        }
    }

    fun share(ctx: Context, lang: String, code: String) {
        try {
            val body = if (lang.isBlank()) code else "```$lang\n$code\n```"
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, if (lang.isBlank()) "代码片段" else "代码片段 · $lang")
                putExtra(Intent.EXTRA_TEXT, body)
            }
            ctx.startActivity(Intent.createChooser(i, "分享代码").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast(ctx, "分享失败")
        }
    }

    /** 用系统文件选择器保存到用户指定位置 */
    fun save(ctx: Context, code: String, name: String) {
        val act = ctx as? Activity
        if (act == null) {
            toast(ctx, "当前环境不支持保存")
            return
        }
        pendingSave = code
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        try {
            act.startActivityForResult(intent, MainActivity.REQ_SAVE_CODE)
        } catch (e: Exception) {
            pendingSave = null
            toast(ctx, "无法打开保存面板")
        }
    }

    fun fileName(lang: String): String {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val ext = if (lang.lowercase() in listOf("md", "markdown")) "md" else "txt"
        return "code-$ts.$ext"
    }

    /** 全屏查看代码 */
    fun fullscreen(ctx: Context, lang: String, code: String, p: Palette) {
        val d = Dialog(ctx)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        root.background = Ui.rounded(p.panel, 14, ctx, p.border, 1)
        root.setPadding(Ui.dp(ctx, 14f), Ui.dp(ctx, 12f), Ui.dp(ctx, 14f), Ui.dp(ctx, 12f))

        val title = LinearLayout(ctx)
        title.orientation = LinearLayout.HORIZONTAL
        title.gravity = Gravity.CENTER_VERTICAL
        val name = Ui.textView(ctx, if (lang.isBlank()) "代码" else lang, 13f, p.text2)
        name.setTypeface(Typeface.MONOSPACE)
        title.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close = Ui.button(ctx, "关闭", false, p, true)
        close.setOnClickListener { d.dismiss() }
        title.addView(close)
        root.addView(title)

        val scroll = ScrollView(ctx)
        val tv = Ui.textView(ctx, CodeHighlight.render(lang, code, p), 12.5f, p.text)
        tv.typeface = Typeface.MONOSPACE
        tv.setTextIsSelectable(true)
        tv.setLineSpacing(Ui.dp(ctx, 2f).toFloat(), 1.35f)
        tv.setPadding(0, Ui.dp(ctx, 10f), 0, Ui.dp(ctx, 10f))
        scroll.addView(tv)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val actions = LinearLayout(ctx)
        actions.orientation = LinearLayout.HORIZONTAL
        val copyBtn = Ui.button(ctx, "复制", false, p, true)
        copyBtn.setOnClickListener { copy(ctx, code) }
        val saveBtn = Ui.button(ctx, "下载", true, p, true)
        saveBtn.setOnClickListener { save(ctx, code, fileName(lang)) }
        val lp1 = LinearLayout.LayoutParams(0, Ui.dp(ctx, 36f), 1f)
        lp1.rightMargin = Ui.dp(ctx, 10f)
        actions.addView(copyBtn, lp1)
        actions.addView(saveBtn, LinearLayout.LayoutParams(0, Ui.dp(ctx, 36f), 1f))
        val al = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        al.topMargin = Ui.dp(ctx, 12f)
        root.addView(actions, al)

        d.setContentView(root)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout((ctx.resources.displayMetrics.widthPixels * 0.92f).toInt(),
            (ctx.resources.displayMetrics.heightPixels * 0.8f).toInt())
        d.show()
        val decor = d.window?.decorView as? ViewGroup
        Overlay.push(decor)
        d.setOnDismissListener { Overlay.remove(decor) }
    }

    fun toast(ctx: Context, msg: String) {
        val act = ctx as? Activity
        if (act != null) Snack.show(act, msg) else {
            try { android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show() } catch (e: Exception) { }
        }
    }
}
