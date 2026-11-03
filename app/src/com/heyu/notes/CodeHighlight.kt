package com.heyu.notes

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan

/**
 * 轻量语法高亮（原生，无 WebView）：
 * HTML / XML、CSS、JS/TS、Java/Kotlin/C 系、Python、JSON、Bash、SQL。
 */
object CodeHighlight {

    private val C_KEYWORDS = setOf(
        "abstract", "as", "break", "case", "catch", "class", "companion", "const", "constructor",
        "continue", "data", "default", "do", "else", "enum", "extends", "false", "final", "finally",
        "for", "fun", "if", "implements", "import", "in", "init", "instanceof", "interface", "internal",
        "is", "lateinit", "let", "new", "null", "object", "open", "override", "package", "private",
        "protected", "public", "return", "sealed", "static", "super", "switch", "synchronized", "this",
        "throw", "throws", "true", "try", "typealias", "typeof", "val", "var", "void", "when", "while",
        "yield", "async", "await", "function", "export", "from", "undefined", "let", "of"
    )
    private val PY_KEYWORDS = setOf(
        "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif",
        "else", "except", "False", "finally", "for", "from", "global", "if", "import", "in", "is",
        "lambda", "None", "nonlocal", "not", "or", "pass", "raise", "return", "True", "try", "while",
        "with", "yield"
    )
    private val BASH_KEYWORDS = setOf(
        "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac", "function",
        "return", "local", "export", "echo", "cd", "source", "set", "unset"
    )
    private val SQL_KEYWORDS = setOf(
        "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE",
        "TABLE", "ALTER", "DROP", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "ON", "GROUP", "BY",
        "ORDER", "HAVING", "LIMIT", "OFFSET", "AS", "AND", "OR", "NOT", "NULL", "PRIMARY", "KEY",
        "INDEX", "DEFAULT", "UNIQUE", "REFERENCES", "CONSTRAINT", "CASE", "WHEN", "THEN", "END"
    )

    fun render(lang: String, code: String, p: Palette): CharSequence {
        val sb = SpannableStringBuilder(code)
        val l = lang.trim().lowercase()
        try {
            when {
                l == "html" || l == "xml" || l == "svg" || l == "vue" -> html(sb, code, p)
                l == "css" || l == "scss" || l == "less" -> css(sb, code, p)
                l == "json" -> json(sb, code, p)
                l == "python" || l == "py" -> python(sb, code, p)
                l == "bash" || l == "sh" || l == "shell" || l == "zsh" -> bash(sb, code, p)
                l == "sql" -> sql(sb, code, p)
                l == "js" || l == "javascript" || l == "ts" || l == "typescript" || l == "kotlin" ||
                    l == "kt" || l == "java" || l == "c" || l == "cpp" || l == "c++" || l == "cs" ||
                    l == "go" || l == "rust" || l == "dart" || l == "php" || l == "swift" ->
                    cLike(sb, code, p, C_KEYWORDS, allowAnnotations = true)
                else -> Unit
            }
        } catch (e: Exception) {
            // 高亮失败不影响原文显示
        }
        return sb
    }

    private fun kw(p: Palette) = p.accent
    private fun strColor(p: Palette) = if (p.dark) 0xFF7EE787.toInt() else 0xFF0B7A3B.toInt()
    private fun numColor(p: Palette) = if (p.dark) 0xFFF0B429.toInt() else 0xFFB45309.toInt()
    private fun tagColor(p: Palette) = if (p.dark) 0xFF7DD3FC.toInt() else 0xFF1D4ED8.toInt()
    private fun attrColor(p: Palette) = if (p.dark) 0xFFFBBF24.toInt() else 0xFF9A3412.toInt()

    private fun paint(sb: SpannableStringBuilder, from: Int, to: Int, color: Int) {
        if (to > from) sb.setSpan(ForegroundColorSpan(color), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /* ---------------- HTML ---------------- */
    private fun html(sb: SpannableStringBuilder, code: String, p: Palette) {
        var i = 0
        val n = code.length
        while (i < n) {
            if (code.startsWith("<!--", i)) {
                val end = code.indexOf("-->", i + 4)
                val e = if (end < 0) n else end + 3
                paint(sb, i, e, p.text3); i = e; continue
            }
            if (code.startsWith("<!", i)) {
                val end = code.indexOf('>', i)
                val e = if (end < 0) n else end + 1
                paint(sb, i, e, p.text3); i = e; continue
            }
            if (code[i] == '<') {
                val end = tagEnd(code, i)
                highlightTag(sb, code, i, end, p)
                // <style>/<script> 内部按 CSS/JS 处理
                val lower = code.substring(i, minOf(end, i + 12)).lowercase()
                if (lower.startsWith("<style")) {
                    val close = code.indexOf("</style", end, ignoreCase = true)
                    if (close > end) { css(sb, code, p, end, close) }
                } else if (lower.startsWith("<script")) {
                    val close = code.indexOf("</script", end, ignoreCase = true)
                    if (close > end) { cLike(sb, code, p, C_KEYWORDS, true, end, close) }
                }
                i = end; continue
            }
            i++
        }
    }

    private fun tagEnd(code: String, start: Int): Int {
        var i = start + 1
        var quote = 0.toChar()
        while (i < code.length) {
            val c = code[i]
            if (quote != 0.toChar()) {
                if (c == quote) quote = 0.toChar()
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '>') {
                return i + 1
            }
            i++
        }
        return code.length
    }

    private fun highlightTag(sb: SpannableStringBuilder, code: String, start: Int, end: Int, p: Palette) {
        var i = start
        // 标签名
        if (i < end && code[i] == '<') {
            i++
            if (i < end && code[i] == '/') i++
            val nameStart = i
            while (i < end && (code[i].isLetterOrDigit() || code[i] == '-' || code[i] == ':')) i++
            paint(sb, start, nameStart, p.text2)
            paint(sb, nameStart, i, tagColor(p))
        }
        // 属性与字符串
        while (i < end) {
            val c = code[i]
            if (c == '"' || c == '\'') {
                val s = i
                i++
                while (i < end && code[i] != c) i++
                if (i < end) i++
                paint(sb, s, i, strColor(p))
            } else if (c.isLetter() || c == '_' || c == ':') {
                val s = i
                while (i < end && (code[i].isLetterOrDigit() || code[i] == '-' || code[i] == ':')) i++
                paint(sb, s, i, attrColor(p))
            } else {
                i++
            }
        }
    }

    /* ---------------- CSS ---------------- */
    private fun css(sb: SpannableStringBuilder, code: String, p: Palette, from: Int = 0, to: Int = code.length) {
        var i = from
        while (i < to) {
            val c = code[i]
            if (c == '/' && i + 1 < to && code[i + 1] == '*') {
                val e = code.indexOf("*/", i + 2).let { if (it < 0 || it > to) to else it + 2 }
                paint(sb, i, e, p.text3); i = e
            } else if (c == '"' || c == '\'') {
                val s = i; i++
                while (i < to && code[i] != c) i++
                if (i < to) i++
                paint(sb, s, i, strColor(p))
            } else if (c == '#' && i + 1 < to && (code[i + 1].isLetterOrDigit())) {
                val s = i; i++
                while (i < to && code[i].isLetterOrDigit()) i++
                paint(sb, s, i, numColor(p))
            } else if (c.isDigit()) {
                val s = i
                while (i < to && (code[i].isDigit() || code[i] == '.' || code[i] == '%')) i++
                paint(sb, s, i, numColor(p))
            } else if (c.isLetter() || c == '-' || c == '@') {
                val s = i
                while (i < to && (code[i].isLetterOrDigit() || code[i] == '-' || code[i] == '@')) i++
                if (i < to && code[i] == ':') paint(sb, s, i, attrColor(p)) else paint(sb, s, i, tagColor(p))
            } else {
                i++
            }
        }
    }

    /* ---------------- JSON ---------------- */
    private fun json(sb: SpannableStringBuilder, code: String, p: Palette) {
        var i = 0
        val n = code.length
        while (i < n) {
            val c = code[i]
            when {
                c == '"' -> {
                    val s = i; i++
                    while (i < n && code[i] != '"') { if (code[i] == '\\') i++; i++ }
                    if (i < n) i++
                    var j = i
                    while (j < n && code[j].isWhitespace()) j++
                    paint(sb, s, i, if (j < n && code[j] == ':') attrColor(p) else strColor(p))
                }
                c.isDigit() || (c == '-' && i + 1 < n && code[i + 1].isDigit()) -> {
                    val s = i; i++
                    while (i < n && (code[i].isDigit() || code[i] == '.' || code[i] == 'e' || code[i] == 'E' || code[i] == '-' || code[i] == '+')) i++
                    paint(sb, s, i, numColor(p))
                }
                c.isLetter() -> {
                    val s = i
                    while (i < n && code[i].isLetter()) i++
                    paint(sb, s, i, kw(p))
                }
                else -> i++
            }
        }
    }

    /* ---------------- Python ---------------- */
    private fun python(sb: SpannableStringBuilder, code: String, p: Palette) {
        var i = 0
        val n = code.length
        while (i < n) {
            val c = code[i]
            when {
                c == '#' -> {
                    var e = code.indexOf('\n', i)
                    if (e < 0) e = n
                    paint(sb, i, e, p.text3); i = e
                }
                code.startsWith("\"\"\"", i) || code.startsWith("'''", i) -> {
                    val q = code.substring(i, i + 3)
                    val e = code.indexOf(q, i + 3).let { if (it < 0) n else it + 3 }
                    paint(sb, i, e, strColor(p)); i = e
                }
                c == '"' || c == '\'' -> {
                    val s = i; i++
                    while (i < n && code[i] != c) { if (code[i] == '\\') i++; i++ }
                    if (i < n) i++
                    paint(sb, s, i, strColor(p))
                }
                c.isDigit() -> {
                    val s = i
                    while (i < n && (code[i].isDigit() || code[i] == '.' || code[i] == 'x')) i++
                    paint(sb, s, i, numColor(p))
                }
                c == '@' -> {
                    val s = i; i++
                    while (i < n && (code[i].isLetterOrDigit() || code[i] == '_' || code[i] == '.')) i++
                    paint(sb, s, i, attrColor(p))
                }
                c.isLetter() || c == '_' -> {
                    val s = i
                    while (i < n && (code[i].isLetterOrDigit() || code[i] == '_')) i++
                    val w = code.substring(s, i)
                    if (w in PY_KEYWORDS) paint(sb, s, i, kw(p))
                }
                else -> i++
            }
        }
    }

    /* ---------------- Bash ---------------- */
    private fun bash(sb: SpannableStringBuilder, code: String, p: Palette) {
        var i = 0
        val n = code.length
        while (i < n) {
            val c = code[i]
            when {
                c == '#' -> {
                    var e = code.indexOf('\n', i)
                    if (e < 0) e = n
                    paint(sb, i, e, p.text3); i = e
                }
                c == '"' || c == '\'' -> {
                    val s = i; i++
                    while (i < n && code[i] != c) { if (code[i] == '\\') i++; i++ }
                    if (i < n) i++
                    paint(sb, s, i, strColor(p))
                }
                c == '$' -> {
                    val s = i; i++
                    while (i < n && (code[i].isLetterOrDigit() || code[i] == '_' || code[i] == '{' || code[i] == '}')) i++
                    paint(sb, s, i, attrColor(p))
                }
                c.isLetter() -> {
                    val s = i
                    while (i < n && (code[i].isLetterOrDigit() || code[i] == '_' || code[i] == '-')) i++
                    if (code.substring(s, i) in BASH_KEYWORDS) paint(sb, s, i, kw(p))
                }
                else -> i++
            }
        }
    }

    /* ---------------- SQL ---------------- */
    private fun sql(sb: SpannableStringBuilder, code: String, p: Palette) {
        var i = 0
        val n = code.length
        while (i < n) {
            val c = code[i]
            when {
                code.startsWith("--", i) -> {
                    var e = code.indexOf('\n', i)
                    if (e < 0) e = n
                    paint(sb, i, e, p.text3); i = e
                }
                c == '\'' -> {
                    val s = i; i++
                    while (i < n && code[i] != '\'') i++
                    if (i < n) i++
                    paint(sb, s, i, strColor(p))
                }
                c.isDigit() -> {
                    val s = i
                    while (i < n && (code[i].isDigit() || code[i] == '.')) i++
                    paint(sb, s, i, numColor(p))
                }
                c.isLetter() -> {
                    val s = i
                    while (i < n && (code[i].isLetterOrDigit() || code[i] == '_')) i++
                    if (code.substring(s, i).uppercase() in SQL_KEYWORDS) paint(sb, s, i, kw(p))
                }
                else -> i++
            }
        }
    }

    /* ---------------- C 系 ---------------- */
    private fun cLike(
        sb: SpannableStringBuilder, code: String, p: Palette, keywords: Set<String>,
        allowAnnotations: Boolean, from: Int = 0, to: Int = code.length
    ) {
        var i = from
        while (i < to) {
            val c = code[i]
            when {
                c == '/' && i + 1 < to && code[i + 1] == '/' -> {
                    var e = code.indexOf('\n', i)
                    if (e < 0 || e > to) e = to
                    paint(sb, i, e, p.text3); i = e
                }
                c == '/' && i + 1 < to && code[i + 1] == '*' -> {
                    val e = code.indexOf("*/", i + 2).let { if (it < 0 || it > to) to else it + 2 }
                    paint(sb, i, e, p.text3); i = e
                }
                c == '"' || c == '\'' || c == '`' -> {
                    val s = i; i++
                    while (i < to && code[i] != c) { if (code[i] == '\\') i++; i++ }
                    if (i < to) i++
                    paint(sb, s, i, strColor(p))
                }
                c.isDigit() -> {
                    val s = i
                    while (i < to && (code[i].isLetterOrDigit() || code[i] == '.' || code[i] == '_')) i++
                    paint(sb, s, i, numColor(p))
                }
                allowAnnotations && c == '@' -> {
                    val s = i; i++
                    while (i < to && (code[i].isLetterOrDigit() || code[i] == '_')) i++
                    paint(sb, s, i, attrColor(p))
                }
                c.isLetter() || c == '_' -> {
                    val s = i
                    while (i < to && (code[i].isLetterOrDigit() || code[i] == '_' || code[i] == '$')) i++
                    if (code.substring(s, i) in keywords) paint(sb, s, i, kw(p))
                }
                else -> i++
            }
        }
    }
}
