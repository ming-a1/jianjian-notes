package com.heyu.notes

import android.content.Context
import android.graphics.Typeface
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** 把 Markdown 块渲染成原生 View（编辑器的预览页） */
object Preview {

    fun render(ctx: Context, container: LinearLayout, markdown: String, p: Palette, baseSize: Float = 16f) {
        container.removeAllViews()
        val blocks = Md.parse(markdown)
        if (blocks.isEmpty()) {
            val tv = Ui.textView(ctx, "（空白笔记）", baseSize, p.text3)
            container.addView(tv, marginParams(ctx, 0, 12))
            return
        }
        blocks.forEach { container.addView(viewFor(ctx, it, p, baseSize)) }
    }

    private fun marginParams(ctx: Context, top: Int, bottom: Int): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = Ui.dp(ctx, top.toFloat())
        lp.bottomMargin = Ui.dp(ctx, bottom.toFloat())
        return lp
    }

    fun viewFor(ctx: Context, b: Block, p: Palette, baseSize: Float): View = when (b) {
        is Block.Heading -> headingView(ctx, b, p, baseSize)
        is Block.Para -> paraView(ctx, b.text, p, baseSize)
        is Block.Code -> codeView(ctx, b, p, baseSize)
        is Block.Quote -> quoteView(ctx, b.text, p, baseSize)
        is Block.Bullets -> listView(ctx, b, p, baseSize)
        is Block.Table -> tableView(ctx, b, p, baseSize)
        Block.Hr -> hrView(ctx, p)
    }

    private fun headingView(ctx: Context, b: Block.Heading, p: Palette, base: Float): TextView {
        val sizes = floatArrayOf(24f, 20.5f, 18f, 16.5f, 15.5f, 15f)
        val size = sizes[(b.level - 1).coerceIn(0, 5)] * (base / 16f)
        val tv = Ui.textView(ctx, Md.spans(ctx, b.text, p, size, p.text), size, p.text)
        tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        tv.setLineSpacing(0f, 1.3f)
        return tv.apply { layoutParams = marginParams(ctx, if (b.level <= 2) 16 else 12, 4) }
    }

    private val IMG_ONLY = Regex("""^!\[([^\]]*)\]\(([^)\s]+)\)\s*$""")

    private fun paraView(ctx: Context, text: String, p: Palette, base: Float): View {
        val m = IMG_ONLY.find(text.trim())
        if (m != null) {
            val maxW = ctx.resources.displayMetrics.widthPixels - Ui.dp(ctx, 56f)
            val bmp = ImageStore.bitmap(ctx, m.groupValues[2], maxW)
            if (bmp != null) {
                val iv = ImageView(ctx)
                iv.setImageBitmap(bmp)
                iv.adjustViewBounds = true
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                iv.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                return iv
            }
        }
        return paraTextTextView(ctx, text, p, base)
    }

    private fun paraTextTextView(ctx: Context, text: String, p: Palette, base: Float): TextView {
        val tv = Ui.textView(ctx, Md.spans(ctx, text, p, base, p.text), base, p.text)
        tv.setLineSpacing(Ui.dp(ctx, 4f).toFloat(), 1.55f)
        tv.movementMethod = LinkMovementMethod.getInstance()
        tv.layoutParams = marginParams(ctx, 5, 5)
        return tv
    }

    private fun codeView(ctx: Context, b: Block.Code, p: Palette, base: Float): View {
        val wrap = LinearLayout(ctx)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.background = Ui.rounded(p.codeBg, 10, ctx, p.border, 1)
        wrap.setPadding(Ui.dp(ctx, 12f), Ui.dp(ctx, 10f), Ui.dp(ctx, 12f), Ui.dp(ctx, 10f))
        val tv = Ui.textView(ctx, b.code, base - 2f, p.text)
        tv.typeface = Typeface.MONOSPACE
        tv.setLineSpacing(Ui.dp(ctx, 2f).toFloat(), 1.35f)
        tv.setTextIsSelectable(true)
        wrap.addView(tv)
        wrap.layoutParams = marginParams(ctx, 8, 8)
        return wrap
    }

    private fun quoteView(ctx: Context, text: String, p: Palette, base: Float): View {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        val bar = View(ctx)
        bar.setBackgroundColor(p.accent)
        row.addView(bar, LinearLayout.LayoutParams(Ui.dp(ctx, 3f), ViewGroup.LayoutParams.MATCH_PARENT))
        val tv = Ui.textView(ctx, Md.spans(ctx, text, p, base - 0.5f, p.text2), base - 0.5f, p.text2)
        tv.setLineSpacing(Ui.dp(ctx, 3f).toFloat(), 1.5f)
        tv.setPadding(Ui.dp(ctx, 12f), Ui.dp(ctx, 6f), Ui.dp(ctx, 8f), Ui.dp(ctx, 6f))
        row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.background = Ui.rounded(p.bg2, 8, ctx)
        row.layoutParams = marginParams(ctx, 8, 8)
        return row
    }

    private fun listView(ctx: Context, b: Block.Bullets, p: Palette, base: Float): View {
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        b.items.forEach { item ->
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.TOP
            val indent = Ui.dp(ctx, (10 + item.level * 18).toFloat())
            val marker = TextView(ctx)
            marker.setTextSize(TypedValue.COMPLEX_UNIT_SP, base - 1f)
            marker.setTextColor(if (item.checked == true) p.accent else p.text2)
            val m = when {
                item.checked == true -> "☑"
                item.checked == false -> "☐"
                b.ordered -> "${item.number}."
                else -> "•"
            }
            marker.text = m
            marker.setPadding(0, 0, Ui.dp(ctx, 8f), 0)
            marker.minWidth = Ui.dp(ctx, if (b.ordered) 22f else 16f)
            row.setPadding(indent, Ui.dp(ctx, 2f), 0, Ui.dp(ctx, 2f))
            row.addView(marker)
            val tv = Ui.textView(ctx, Md.spans(ctx, item.text, p, base, p.text), base, p.text)
            tv.setLineSpacing(Ui.dp(ctx, 3f).toFloat(), 1.5f)
            row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            col.addView(row)
        }
        col.layoutParams = marginParams(ctx, 4, 6)
        return col
    }

    private fun tableView(ctx: Context, b: Block.Table, p: Palette, base: Float): View {
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        val rows = ArrayList<List<String>>()
        rows.add(b.header)
        rows.addAll(b.rows)
        val cols = b.header.size
        rows.forEachIndexed { index, cells ->
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            for (c in 0 until cols) {
                val cell = Ui.textView(ctx, cells.getOrElse(c) { "" }, base - 2f, p.text)
                cell.setTypeface(Typeface.DEFAULT, if (index == 0) Typeface.BOLD else Typeface.NORMAL)
                cell.setPadding(Ui.dp(ctx, 10f), Ui.dp(ctx, 7f), Ui.dp(ctx, 10f), Ui.dp(ctx, 7f))
                cell.background = Ui.rounded(if (index == 0) p.bg2 else p.bg, 0, ctx, p.border, 1)
                row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            col.addView(row)
        }
        col.layoutParams = marginParams(ctx, 8, 8)
        return col
    }

    private fun hrView(ctx: Context, p: Palette): View {
        val v = View(ctx)
        v.setBackgroundColor(p.border)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 1f))
        lp.topMargin = Ui.dp(ctx, 14f)
        lp.bottomMargin = Ui.dp(ctx, 14f)
        v.layoutParams = lp
        return v
    }
}
