package com.heyu.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 把笔记画成一张 PNG 图片（宽度按手机屏幕定，字体按比例缩放） */
object ShareImage {

    private data class Piece(val layout: StaticLayout, val x: Int, val top: Int, val bg: RectF?, val bar: RectF?)
    private data class Line(val top: Int, val color: Int)
    private data class Img(val bmp: Bitmap, val left: Int, val top: Int, val w: Int, val h: Int)

    private class Metrics(ctx: Context) {
        val dm = ctx.resources.displayMetrics
        val width: Int = dm.widthPixels.coerceIn(900, 1440)
        val scale: Float = width / 1080f
        val pad: Int = (72 * scale).toInt()
        fun s(v: Float): Float = v * scale
        fun i(v: Float): Int = (v * scale).toInt()
    }

    fun render(ctx: Context, note: Note, p: Palette): Bitmap {
        val m = Metrics(ctx)
        val W = m.width
        val pad = m.pad
        val contentW = W - pad * 2
        val pieces = ArrayList<Piece>()
        val lines = ArrayList<Line>()
        val images = ArrayList<Img>()
        var y = pad

        fun paint(size: Float, color: Int, bold: Boolean = false, mono: Boolean = false) = TextPaint().apply {
            isAntiAlias = true
            textSize = size
            this.color = color
            typeface = when {
                mono -> Typeface.MONOSPACE
                bold -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                else -> Typeface.DEFAULT
            }
        }

        fun add(
            text: CharSequence, size: Float, color: Int, bold: Boolean = false, mono: Boolean = false,
            x: Int = pad, width: Int = contentW, bg: RectF? = null, bar: RectF? = null, gap: Int = 0
        ) {
            val tp = paint(size, color, bold, mono)
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, width)
                .setLineSpacing(0f, 1.45f)
                .setIncludePad(false)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()
            pieces.add(
                Piece(layout, x, y,
                    bg?.let { RectF(it.left, it.top + y, it.right, it.bottom + y) },
                    bar?.let { RectF(it.left, it.top + y, it.right, it.bottom + y) })
            )
            y += layout.height + gap
        }

        add(Md.spans(ctx, note.displayTitle(), p, m.s(72f), p.text), m.s(72f), p.text, bold = true, gap = m.i(26f))
        val d = Date(note.updatedAt.coerceAtLeast(System.currentTimeMillis()))
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d)
        add("$dateStr · ${Md.plainText(note.content).length} 字 · 简笺笔记", m.s(30f), p.text3, gap = m.i(34f))
        lines.add(Line(y, p.border))
        y += m.i(40f)

        val blocks = Md.parse(note.content)
        if (blocks.isEmpty()) {
            add("（空白笔记）", m.s(40f), p.text3, gap = m.i(20f))
        }
        for (b in blocks) {
            when (b) {
                is Block.Heading -> {
                    val size = when (b.level) {
                        1 -> m.s(62f); 2 -> m.s(52f); 3 -> m.s(46f); else -> m.s(42f)
                    }
                    add(Md.text(ctx, b.inl, b.text, p, size, p.text), size, p.text, bold = true)
                    y += m.i(18f)
                }
                is Block.Para -> {
                    val img = b.inl.singleOrNull() as? Inl.Img
                    val bmp = if (img != null) ImageStore.bitmap(ctx, img.src, contentW) else null
                    if (bmp != null && bmp.width > 0) {
                        val w = contentW
                        val h = (bmp.height.toFloat() * w / bmp.width).toInt()
                        images.add(Img(bmp, pad, y, w, h))
                        y += h + m.i(24f)
                    } else {
                        add(Md.text(ctx, b.inl, b.text, p, m.s(40f), p.text), m.s(40f), p.text, gap = m.i(24f))
                    }
                }
                is Block.Code -> {
                    val size = m.s(34f)
                    val tp = paint(size, p.text, mono = true)
                    val hl = CodeHighlight.render(b.lang, b.code, p)
                    val layout = StaticLayout.Builder.obtain(hl, 0, hl.length, tp, contentW - m.i(48f))
                        .setLineSpacing(0f, 1.4f).build()
                    val bg = RectF(pad.toFloat(), (y - m.i(18f)).toFloat(), (W - pad).toFloat(), (y + layout.height + m.i(18f)).toFloat())
                    pieces.add(Piece(layout, pad + m.i(24f), y, bg, null))
                    y += layout.height + m.i(60f)
                }
                is Block.Quote -> {
                    val size = m.s(38f)
                    val spans = Md.spans(ctx, b.text, p, size, p.text2)
                    val tp = paint(size, p.text2)
                    val layout = StaticLayout.Builder.obtain(spans, 0, spans.length, tp, contentW - m.i(40f))
                        .setLineSpacing(0f, 1.5f).build()
                    val bar = RectF((pad + m.i(2f)).toFloat(), (y - m.i(4f)).toFloat(), (pad + m.i(8f)).toFloat(), (y + layout.height + m.i(4f)).toFloat())
                    pieces.add(Piece(layout, pad + m.i(28f), y, null, bar))
                    y += layout.height + m.i(36f)
                }
                is Block.Bullets -> {
                    val size = m.s(40f)
                    for (item in b.items) {
                        val prefix = when {
                            item.checked == true -> "☑ "
                            item.checked == false -> "☐ "
                            b.ordered -> "${item.number}. "
                            else -> "• "
                        }
                        val tp = paint(size, p.text)
                        val text = android.text.SpannableStringBuilder(prefix)
                            .append(Md.text(ctx, item.inl, item.text, p, size, p.text))
                        val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, contentW - m.i(40f))
                            .setLineSpacing(0f, 1.5f).build()
                        pieces.add(Piece(layout, pad + m.i(10f) + item.level * m.i(28f), y, null, null))
                        y += layout.height + m.i(10f)
                    }
                    y += m.i(14f)
                }
                is Block.Table -> {
                    val size = m.s(34f)
                    for ((idx, row) in (listOf(b.header) + b.rows).withIndex()) {
                        val tp = paint(size, p.text, mono = true)
                        val line = row.joinToString("   ")
                        val layout = StaticLayout.Builder.obtain(line, 0, line.length, tp, contentW).build()
                        val bg = if (idx == 0) RectF(pad.toFloat(), y.toFloat(), (W - pad).toFloat(), (y + layout.height + m.i(10f)).toFloat()) else null
                        pieces.add(Piece(layout, pad, y, bg, null))
                        y += layout.height + m.i(12f)
                    }
                    y += m.i(20f)
                }
                is Block.Html -> {
                    val size = m.s(32f)
                    val tp = paint(size, p.text2, mono = true)
                    val html = if (b.text.length > 400) b.text.substring(0, 400) + "…" else b.text
                    val layout = StaticLayout.Builder.obtain(html, 0, html.length, tp, contentW - m.i(24f)).build()
                    pieces.add(Piece(layout, pad, y, null, null))
                    y += layout.height + m.i(30f)
                }
                Block.Hr -> { lines.add(Line(y, p.border)); y += m.i(40f) }
            }
        }

        y += m.i(26f)
        lines.add(Line(y, p.border))
        y += m.i(26f)
        pieces.add(Piece(
            StaticLayout.Builder.obtain("简笺 · Markdown 笔记", 0, 9, paint(m.s(26f), p.text3), contentW).build(),
            pad, y, null, null
        ))
        y += m.i(66f)

        val bmp = Bitmap.createBitmap(W, y, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(p.bg)
        for (l in lines) {
            val lp = Paint().apply { color = l.color; strokeWidth = 2f }
            c.drawLine(pad.toFloat(), l.top.toFloat(), (W - pad).toFloat(), l.top.toFloat(), lp)
        }
        for (im in images) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            c.drawBitmap(im.bmp, null, RectF(im.left.toFloat(), im.top.toFloat(),
                (im.left + im.w).toFloat(), (im.top + im.h).toFloat()), paint)
        }
        for (piece in pieces) {
            piece.bg?.let {
                val bgPaint = Paint().apply { color = p.codeBg; isAntiAlias = true }
                c.drawRoundRect(it, 18f, 18f, bgPaint)
            }
            piece.bar?.let {
                val barPaint = Paint().apply { color = p.accent; isAntiAlias = true }
                c.drawRoundRect(it, 4f, 4f, barPaint)
            }
            c.save()
            c.translate(piece.x.toFloat(), piece.top.toFloat())
            piece.layout.draw(c)
            c.restore()
        }
        return bmp
    }

    fun share(ctx: Context, note: Note, p: Palette) {
        val bmp = render(ctx, note, p)
        val dir = ShareProvider.shareDir(ctx)
        val name = "jianjian-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".png"
        val f = java.io.File(dir, name)
        java.io.FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = ShareProvider.uriFor(name)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(android.content.Intent.createChooser(send, "分享笔记图片")
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
