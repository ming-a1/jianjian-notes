package com.heyu.notes

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.View
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/* ============ 行内节点 ============ */
sealed class Inl {
    data class T(val s: String) : Inl()
    data class Code(val s: String) : Inl()
    data class Emph(val c: List<Inl>) : Inl()
    data class Strong(val c: List<Inl>) : Inl()
    data class Strike(val c: List<Inl>) : Inl()
    data class Link(val url: String, val c: List<Inl>) : Inl()
    data class Img(val src: String, val alt: String) : Inl()
    object Soft : Inl()
    object Hard : Inl()
    data class Html(val s: String) : Inl()
}

/* ============ 块级节点 ============ */
sealed class Block {
    data class Heading(val level: Int, val text: String, val inl: List<Inl> = emptyList()) : Block()
    data class Para(val text: String, val inl: List<Inl> = emptyList()) : Block()
    data class Code(val lang: String, val code: String) : Block()
    data class Quote(val text: String, val blocks: List<Block> = emptyList()) : Block()
    data class Bullets(val ordered: Boolean, val items: List<Item>) : Block()
    data class Table(
        val header: List<String>,
        val rows: List<List<String>>,
        val headerInl: List<List<Inl>> = emptyList(),
        val rowsInl: List<List<List<Inl>>> = emptyList()
    ) : Block()
    data class Html(val text: String) : Block()
    object Hr : Block()
}

data class Item(
    val text: String,
    val checked: Boolean?,
    val level: Int,
    val number: Int,
    val inl: List<Inl> = emptyList()
)

/**
 * 基于 commonmark-java（CommonMark + GFM 扩展）的解析与原生渲染：
 * 表格、删除线、自动链接、任务清单、脚注、嵌套引用/列表、HTML 块、硬换行等。
 */
object Md {

    private val extensions = listOf(
        TablesExtension.create(),
        StrikethroughExtension.create(),
        AutolinkExtension.create(),
        TaskListItemsExtension.create()
    )
    private val parser: Parser by lazy {
        Parser.builder().extensions(extensions).build()
    }

    /* ---------------- 解析 ---------------- */
    fun parse(src: String): List<Block> {
        val (text, footnotes) = preprocessFootnotes(src)
        val doc = parser.parse(text)
        val out = ArrayList<Block>()
        walk(doc, out)
        if (footnotes.isNotEmpty()) {
            out.add(Block.Quote(footnotes.joinToString("\n")))
        }
        return out
    }

    /** 把一段文本按行内语法解析（兼容旧接口） */
    fun parseInline(raw: String): List<Inl> {
        if (raw.isEmpty()) return emptyList()
        val doc = parser.parse(raw)
        val p = doc.firstChild as? Paragraph ?: return listOf(Inl.T(raw))
        return inlines(p)
    }

    private fun walk(parent: Node, out: MutableList<Block>) {
        var c = parent.firstChild
        while (c != null) {
            when (c) {
                is Heading -> out.add(Block.Heading(c.level, plain(inlines(c)), inlines(c)))
                is Paragraph -> out.add(Block.Para(plain(inlines(c)), inlines(c)))
                is FencedCodeBlock -> out.add(Block.Code(c.info.trim(), c.literal.trimEnd('\n')))
                is IndentedCodeBlock -> out.add(Block.Code("", c.literal.trimEnd('\n')))
                is BlockQuote -> {
                    val sub = ArrayList<Block>()
                    walk(c, sub)
                    out.add(Block.Quote(plainBlocks(sub), sub))
                }
                is BulletList -> {
                    val items = ArrayList<Item>()
                    collectList(c, 0, false, 1, items)
                    out.add(Block.Bullets(false, items))
                }
                is OrderedList -> {
                    val items = ArrayList<Item>()
                    collectList(c, 0, true, c.startNumber, items)
                    out.add(Block.Bullets(true, items))
                }
                is ThematicBreak -> out.add(Block.Hr)
                is HtmlBlock -> out.add(Block.Html(c.literal.trim()))
                is TableBlock -> out.add(parseTable(c))
                else -> if (c.firstChild != null) walk(c, out)
            }
            c = c.next
        }
    }

    private fun collectList(list: Node, level: Int, ordered: Boolean, start: Int, out: MutableList<Item>) {
        var idx = 0
        var c = list.firstChild
        while (c != null) {
            if (c is ListItem) {
                idx++
                var checked: Boolean? = null
                val marker = c.firstChild
                if (marker is TaskListItemMarker) checked = marker.isChecked

                var firstPara: Node? = null
                var sub = c.firstChild
                while (sub != null) {
                    if (sub is Paragraph && firstPara == null) firstPara = sub
                    sub = sub.next
                }
                val inl = if (firstPara != null) inlines(firstPara) else emptyList()
                val num = if (ordered) start + idx - 1 else idx
                out.add(Item(plain(inl), checked, level, num, inl))

                // 子列表（层级 +1）
                sub = c.firstChild
                while (sub != null) {
                    when (sub) {
                        is BulletList -> collectList(sub, level + 1, false, 1, out)
                        is OrderedList -> collectList(sub, level + 1, true, sub.startNumber, out)
                        else -> { }
                    }
                    sub = sub.next
                }
            }
            c = c.next
        }
    }

    private fun parseTable(t: Node): Block.Table {
        var head: List<List<Inl>> = emptyList()
        val rows = ArrayList<List<List<Inl>>>()
        var c = t.firstChild
        while (c != null) {
            when (c) {
                is TableHead -> {
                    val r = c.firstChild as? TableRow
                    if (r != null) head = cells(r)
                }
                is TableBody -> {
                    var r = c.firstChild
                    while (r != null) {
                        if (r is TableRow) rows.add(cells(r))
                        r = r.next
                    }
                }
            }
            c = c.next
        }
        return Block.Table(head.map { plain(it) }, rows.map { row -> row.map { plain(it) } }, head, rows)
    }

    private fun cells(row: TableRow): List<List<Inl>> {
        val out = ArrayList<List<Inl>>()
        var c = row.firstChild
        while (c != null) {
            if (c is TableCell) out.add(inlines(c))
            c = c.next
        }
        return out
    }

    private fun inlines(node: Node): List<Inl> {
        val out = ArrayList<Inl>()
        var c = node.firstChild
        while (c != null) {
            out.add(inline(c))
            c = c.next
        }
        return out
    }

    private fun inline(n: Node): Inl = when (n) {
        is Text -> Inl.T(n.literal)
        is Code -> Inl.Code(n.literal)
        is Emphasis -> Inl.Emph(inlines(n))
        is StrongEmphasis -> Inl.Strong(inlines(n))
        is Strikethrough -> Inl.Strike(inlines(n))
        is Link -> Inl.Link(n.destination, inlines(n))
        is Image -> Inl.Img(n.destination, plain(inlines(n)))
        is SoftLineBreak -> Inl.Soft
        is HardLineBreak -> Inl.Hard
        is HtmlInline -> Inl.Html(n.literal)
        is TaskListItemMarker -> Inl.T("")
        else -> Inl.T(textOf(n))
    }

    private fun textOf(n: Node): String {
        val sb = StringBuilder()
        var c = n.firstChild
        while (c != null) {
            when (c) {
                is Text -> sb.append(c.literal)
                is Code -> sb.append(c.literal)
                is SoftLineBreak, is HardLineBreak -> sb.append(' ')
                else -> sb.append(textOf(c))
            }
            c = c.next
        }
        return sb.toString()
    }

    private fun plain(list: List<Inl>): String {
        val sb = StringBuilder()
        for (x in list) {
            when (x) {
                is Inl.T -> sb.append(x.s)
                is Inl.Code -> sb.append(x.s)
                is Inl.Emph -> sb.append(plain(x.c))
                is Inl.Strong -> sb.append(plain(x.c))
                is Inl.Strike -> sb.append(plain(x.c))
                is Inl.Link -> sb.append(plain(x.c))
                is Inl.Img -> sb.append(if (x.alt.isEmpty()) "图片" else x.alt)
                is Inl.Soft -> sb.append(' ')
                is Inl.Hard -> sb.append('\n')
                is Inl.Html -> sb.append(x.s)
            }
        }
        return sb.toString().trim()
    }

    private fun plainBlocks(blocks: List<Block>): String =
        blocks.joinToString("\n") {
            when (it) {
                is Block.Heading -> it.text
                is Block.Para -> it.text
                is Block.Code -> it.code
                is Block.Quote -> it.text
                is Block.Bullets -> it.items.joinToString("\n") { i -> i.text }
                is Block.Table -> (listOf(it.header) + it.rows).joinToString("\n") { r -> r.joinToString(" | ") }
                is Block.Html -> it.text
                Block.Hr -> "---"
            }
        }

    /* ---------------- 脚注（手工预处理） ---------------- */
    private val FOOT_DEF = Regex("""^\[\^([^\]]+)\]:\s*(.*)$""")
    private val FOOT_REF = Regex("""\[\^([^\]]+)\]""")

    private fun preprocessFootnotes(src: String): Pair<String, List<String>> {
        val lines = src.replace("\r\n", "\n").split("\n")
        val defs = LinkedHashMap<String, String>()
        val kept = ArrayList<String>()
        var inCode = false
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            if (t.startsWith("```")) { inCode = !inCode; kept.add(line); i++; continue }
            if (!inCode) {
                val m = FOOT_DEF.find(t)
                if (m != null) {
                    val sb = StringBuilder(m.groupValues[2])
                    i++
                    while (i < lines.size && lines[i].isNotEmpty() &&
                        (lines[i].startsWith("    ") || lines[i].startsWith("\t"))) {
                        sb.append(' ').append(lines[i].trim()); i++
                    }
                    defs[m.groupValues[1]] = sb.toString()
                    continue
                }
            }
            kept.add(line)
            i++
        }
        var text = kept.joinToString("\n")
        val numbers = LinkedHashMap<String, Int>()
        var n = 0
        text = FOOT_REF.replace(text) { m ->
            val num = numbers.getOrPut(m.groupValues[1]) { ++n }
            "[$num]"
        }
        val list = ArrayList<String>()
        var k = 0
        for (v in defs.values) { k++; list.add("$k. $v") }
        return text to list
    }

    /* ---------------- 标题 / 纯文本 ---------------- */
    fun title(md: String): String {
        for (line in md.split("\n")) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val h = Regex("""^(#{1,6})\s+(.*)$""").find(t)
            if (h != null) return plain(parseInline(h.groupValues[2]))
            return plain(parseInline(t))
        }
        return ""
    }

    fun plainText(md: String): String {
        val blocks = try { parse(md) } catch (e: Exception) { return "" }
        val sb = StringBuilder()
        for (b in blocks) {
            val s = when (b) {
                is Block.Para -> plain(b.inl)
                is Block.Heading -> plain(b.inl)
                is Block.Quote -> b.text
                is Block.Bullets -> b.items.joinToString(" ") { it.text }
                is Block.Table -> (listOf(b.header) + b.rows).joinToString(" ") { it.joinToString(" ") }
                is Block.Html -> ""
                is Block.Code -> ""
                Block.Hr -> ""
            }
            if (s.isNotBlank()) sb.append(s.trim()).append(' ')
        }
        return sb.toString().trim()
    }

    /* ---------------- 渲染 ---------------- */
    private class St(val bold: Boolean = false, val italic: Boolean = false, val strike: Boolean = false)

    fun render(ctx: Context, list: List<Inl>, p: Palette, sizeSp: Float, color: Int): CharSequence {
        val sb = SpannableStringBuilder()
        emit(ctx, sb, list, p, St(), null)
        return sb
    }

    fun text(ctx: Context, inl: List<Inl>, raw: String, p: Palette, sizeSp: Float, color: Int): CharSequence =
        if (inl.isNotEmpty()) render(ctx, inl, p, sizeSp, color) else spans(ctx, raw, p, sizeSp, color)

    /** 兼容旧接口：把一段文本按行内语法渲染 */
    fun spans(ctx: Context, raw: String, p: Palette, sizeSp: Float, color: Int): CharSequence {
        if (raw.isEmpty()) return ""
        return render(ctx, parseInline(raw), p, sizeSp, color)
    }

    private fun emit(ctx: Context, sb: SpannableStringBuilder, list: List<Inl>, p: Palette, st: St, link: String?) {
        for (x in list) {
            when (x) {
                is Inl.T -> appendText(sb, x.s, p, st)
                is Inl.Code -> {
                    val from = sb.length
                    sb.append(x.s)
                    sb.setSpan(TypefaceSpan("monospace"), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(BackgroundColorSpan(p.codeBg), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(p.danger), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is Inl.Emph -> emit(ctx, sb, x.c, p, St(st.bold, true, st.strike), link)
                is Inl.Strong -> emit(ctx, sb, x.c, p, St(true, st.italic, st.strike), link)
                is Inl.Strike -> emit(ctx, sb, x.c, p, St(st.bold, st.italic, true), link)
                is Inl.Link -> {
                    val from = sb.length
                    emit(ctx, sb, x.c, p, st, x.url)
                    if (sb.length > from) applyLink(ctx, sb, from, sb.length, p, x.url)
                }
                is Inl.Img -> {
                    val label = if (x.alt.isEmpty()) "图片" else x.alt
                    appendText(sb, "🖼 $label", p, st)
                }
                is Inl.Soft -> sb.append(' ')
                is Inl.Hard -> sb.append('\n')
                is Inl.Html -> sb.append(x.s)
            }
        }
    }

    private fun appendText(sb: SpannableStringBuilder, s: String, p: Palette, st: St) {
        if (s.isEmpty()) return
        val from = sb.length
        sb.append(s)
        if (st.bold) sb.setSpan(StyleSpan(Typeface.BOLD), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (st.italic) sb.setSpan(StyleSpan(Typeface.ITALIC), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (st.strike) sb.setSpan(StrikethroughSpan(), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun applyLink(ctx: Context, sb: SpannableStringBuilder, from: Int, to: Int, p: Palette, url: String) {
        sb.setSpan(ForegroundColorSpan(p.accent), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) {
                try {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) { }
            }
            override fun updateDrawState(ds: TextPaint) {
                ds.color = p.accent
                ds.isUnderlineText = true
            }
        }, from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
