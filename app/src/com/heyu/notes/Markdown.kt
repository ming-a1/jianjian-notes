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

/** Markdown 块级结构 */
sealed class Block {
    data class Heading(val level: Int, val text: String) : Block()
    data class Para(val text: String) : Block()
    data class Code(val lang: String, val code: String) : Block()
    data class Quote(val text: String) : Block()
    data class Bullets(val ordered: Boolean, val items: List<Item>) : Block()
    data class Table(val header: List<String>, val rows: List<List<String>>) : Block()
    object Hr : Block()
}

data class Item(val text: String, val checked: Boolean?, val level: Int, val number: Int)

/** Markdown 解析 + 原生 Spannable 渲染（无 WebView） */
object Md {

    private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
    private val HR = Regex("""^(-{3,}|\*{3,}|_{3,})$""")
    private val LIST = Regex("""^(\s*)([-*+]|(\d+)\.)\s+(.*)$""")
    private val TASK = Regex("""^\[([ xX])\]\s+(.*)$""")
    private val TABLE_SEP = Regex("""^\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?$""")
    private val FOOT_DEF = Regex("""^\[\^([^\]]+)\]:\s*(.*)$""")

    fun parse(src: String): List<Block> {
        val lines = src.replace("\r\n", "\n").split("\n")
        val footnotes = LinkedHashMap<String, String>()
        val kept = ArrayList<String>()
        for (l in lines) {
            val m = FOOT_DEF.find(l.trim())
            if (m != null) footnotes[m.groupValues[1]] = m.groupValues[2] else kept.add(l)
        }
        val out = ArrayList<Block>()
        var i = 0
        while (i < kept.size) {
            val raw = kept[i]
            val t = raw.trim()
            if (t.isEmpty()) { i++; continue }

            if (t.startsWith("```")) {
                val lang = t.removePrefix("```").trim()
                i++
                val sb = StringBuilder()
                while (i < kept.size && !kept[i].trim().startsWith("```")) {
                    sb.append(kept[i]).append('\n'); i++
                }
                if (i < kept.size) i++
                out.add(Block.Code(lang, sb.toString().trimEnd('\n')))
                continue
            }

            val hm = HEADING.find(t)
            if (hm != null) {
                out.add(Block.Heading(hm.groupValues[1].length, hm.groupValues[2].trim()))
                i++
                continue
            }

            if (i + 1 < kept.size) {
                val next = kept[i + 1].trim()
                if (next.matches(Regex("^=+$"))) { out.add(Block.Heading(1, t)); i += 2; continue }
                if (next.matches(Regex("^-+$")) && !HR.matches(t)) { out.add(Block.Heading(2, t)); i += 2; continue }
            }

            if (HR.find(t) != null) { out.add(Block.Hr); i++; continue }

            if (t.startsWith(">")) {
                val sb = StringBuilder()
                while (i < kept.size && kept[i].trim().startsWith(">")) {
                    sb.append(kept[i].trim().removePrefix(">").removePrefix(" ")).append('\n'); i++
                }
                out.add(Block.Quote(sb.toString().trimEnd('\n')))
                continue
            }

            if (t.contains("|") && i + 1 < kept.size && TABLE_SEP.matches(kept[i + 1].trim())) {
                val header = splitRow(kept[i])
                i += 2
                val rows = ArrayList<List<String>>()
                while (i < kept.size && kept[i].trim().contains("|") && kept[i].trim().isNotEmpty()) {
                    rows.add(splitRow(kept[i])); i++
                }
                out.add(Block.Table(header, rows))
                continue
            }

            val lm = LIST.find(raw)
            if (lm != null) {
                val ordered = lm.groupValues[3].isNotEmpty()
                val items = ArrayList<Item>()
                while (i < kept.size) {
                    val m2 = LIST.find(kept[i]) ?: break
                    val level = m2.groupValues[1].length / 2
                    var text = m2.groupValues[4]
                    var checked: Boolean? = null
                    val tm = TASK.find(text.trim())
                    if (tm != null) { checked = tm.groupValues[1].equals("x", true); text = tm.groupValues[2] }
                    val num = m2.groupValues[3].toIntOrNull() ?: 1
                    items.add(Item(text, checked, level, num))
                    i++
                }
                out.add(Block.Bullets(ordered, items))
                continue
            }

            val sb = StringBuilder()
            while (i < kept.size) {
                val cur = kept[i]
                val ct = cur.trim()
                if (ct.isEmpty()) break
                if (ct.startsWith("```") || ct.startsWith(">") || HEADING.find(ct) != null || HR.find(ct) != null) break
                if (LIST.find(cur) != null) break
                if (ct.contains("|") && i + 1 < kept.size && TABLE_SEP.matches(kept[i + 1].trim())) break
                sb.append(ct).append('\n'); i++
            }
            out.add(Block.Para(sb.toString().trimEnd('\n')))
        }
        if (footnotes.isNotEmpty()) {
            val sb = StringBuilder()
            footnotes.entries.forEachIndexed { idx, e -> sb.append(idx + 1).append(". ").append(e.value).append('\n') }
            out.add(Block.Quote(sb.toString().trimEnd('\n')))
        }
        return out
    }

    private fun splitRow(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|")) s = s.substring(0, s.length - 1)
        return s.split("|").map { it.trim() }
    }

    fun title(md: String): String {
        for (line in md.split("\n")) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val h = HEADING.find(t)
            if (h != null) return h.groupValues[2].trim()
            return t.replace(Regex("^[-*+]\\s+"), "")
                .replace(Regex("^\\d+\\.\\s+"), "")
                .replace(Regex("[*_`~]"), "")
                .trim()
        }
        return ""
    }

    fun plainText(md: String): String {
        val sb = StringBuilder()
        var inCode = false
        for (line in md.split("\n")) {
            val t = line.trim()
            if (t.startsWith("```")) { inCode = !inCode; continue }
            if (inCode) continue
            if (t.isEmpty()) continue
            var s = t
                .replace(Regex("^#{1,6}\\s+"), "")
                .replace(Regex("^>\\s?"), "")
                .replace(Regex("^[-*+]\\s+\\[[ xX]\\]\\s*"), " ")
                .replace(Regex("^[-*+]\\s+"), " ")
                .replace(Regex("^\\d+\\.\\s+"), " ")
                .replace(Regex("!\\[([^\\]]*)\\]\\([^)]*\\)"), " [图片] ")
                .replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
                .replace(Regex("[*_`~|]"), " ")
            s = s.replace(Regex("\\s+"), " ").trim()
            if (s.isNotEmpty()) sb.append(s).append(' ')
        }
        return sb.toString().trim()
    }

    /* ---------------- 行内样式 ---------------- */
    fun spans(ctx: Context, raw: String, p: Palette, sizeSp: Float, color: Int): CharSequence {
        val out = SpannableStringBuilder()
        var i = 0
        val s = raw
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length -> { out.append(s[i + 1]); i += 2 }
                c == '`' -> {
                    val end = s.indexOf('`', i + 1)
                    if (end > i) {
                        val from = out.length
                        out.append(s.substring(i + 1, end))
                        out.setSpan(TypefaceSpan("monospace"), from, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        out.setSpan(BackgroundColorSpan(p.codeBg), from, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        out.setSpan(ForegroundColorSpan(p.danger), from, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 1
                    } else { out.append(c); i++ }
                }
                s.startsWith("**", i) || s.startsWith("__", i) -> {
                    val mark = s.substring(i, i + 2)
                    val end = s.indexOf(mark, i + 2)
                    if (end > i) {
                        val from = out.length
                        out.append(s.substring(i + 2, end))
                        out.setSpan(StyleSpan(Typeface.BOLD), from, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 2
                    } else { out.append(c); i++ }
                }
                s.startsWith("~~", i) -> {
                    val end = s.indexOf("~~", i + 2)
                    if (end > i) {
                        val from = out.length
                        out.append(s.substring(i + 2, end))
                        out.setSpan(StrikethroughSpan(), from, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 2
                    } else { out.append(c); i++ }
                }
                c == '*' || c == '_' -> {
                    val end = s.indexOf(c, i + 1)
                    if (end > i + 1) {
                        val from = out.length
                        out.append(s.substring(i + 1, end))
                        out.setSpan(StyleSpan(Typeface.ITALIC), from, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 1
                    } else { out.append(c); i++ }
                }
                s.startsWith("![", i) -> {
                    val close = s.indexOf("](", i)
                    val end = if (close > i) s.indexOf(')', close) else -1
                    if (close > i && end > close) {
                        out.append("🖼 ").append(s.substring(i + 2, close))
                        i = end + 1
                    } else { out.append(c); i++ }
                }
                c == '[' -> {
                    val close = s.indexOf("](", i)
                    val end = if (close > i) s.indexOf(')', close) else -1
                    if (close > i && end > close) {
                        val label = s.substring(i + 1, close)
                        val url = s.substring(close + 2, end)
                        val from = out.length
                        out.append(label)
                        out.setSpan(ForegroundColorSpan(p.accent), from, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        out.setSpan(object : ClickableSpan() {
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
                        }, from, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 1
                    } else { out.append(c); i++ }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out
    }
}
