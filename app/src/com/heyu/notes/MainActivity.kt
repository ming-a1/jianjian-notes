package com.heyu.notes

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView

/** 原生 Kotlin 主界面：列表 / 编辑 / 预览 / 设置 / 分享 */
class MainActivity : Activity() {

    private lateinit var repo: NotesRepo
    private lateinit var pal: Palette
    private lateinit var root: LinearLayout
    private lateinit var contentBox: FrameLayout

    private var screen = "list"
    private var current: Note? = null
    private var previewing = false
    private var search = ""
    private var notes: List<Note> = emptyList()

    private val uiHandler = Handler(Looper.getMainLooper())
    private var saveRunnable: Runnable? = null
    private var editor: EditText? = null
    private var titleInput: EditText? = null
    private var toolScroll: HorizontalScrollView? = null
    private val undoStack = ArrayDeque<Pair<String, Int>>()
    private val redoStack = ArrayDeque<Pair<String, Int>>()
    private var lastSnap = 0L
    private var lastText = ""
    private var lastSel = 0
    private var suppressSnap = false
    private var metaView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = NotesRepo(this)
        pal = Theme.palette(this)
        Theme.applyWindow(this, pal)
        notes = repo.all()
        buildRoot()
        if (screen == "note" && current == null) screen = "list"
        renderScreen()
        uiHandler.postDelayed({ UpdateChecker.check(this, pal, false) }, 1200)
    }

    private fun buildRoot() {
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(pal.bg)
        contentBox = FrameLayout(this)
        root.addView(contentBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun renderScreen() {
        contentBox.removeAllViews()
        if (screen == "note" && current != null) contentBox.addView(buildNoteScreen())
        else contentBox.addView(buildListScreen())
    }

    /* ---------------- 列表 ---------------- */
    private fun buildListScreen(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(0, Ui.dp(this, 10f), 0, 0)

        val head = Ui.row(this)
        head.setPadding(Ui.dp(this, 16f), Ui.dp(this, 4f), Ui.dp(this, 10f), Ui.dp(this, 6f))
        val logo = Ui.textView(this, "简笺", 19f, pal.text)
        logo.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        val sub = Ui.hint(this, "  Markdown 笔记", pal, 11.5f)
        head.addView(logo)
        head.addView(sub)
        head.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        val themeBtn = Ui.iconButton(this, if (pal.dark) R.drawable.ic_sun else R.drawable.ic_moon, pal.text2)
        themeBtn.setOnClickListener { toggleTheme() }
        val setBtn = Ui.iconButton(this, R.drawable.ic_settings, pal.text2)
        setBtn.setOnClickListener { openSettings() }
        head.addView(themeBtn)
        head.addView(setBtn)
        col.addView(head)

        val searchBox = Ui.editText(this, "搜索标题或正文…", pal, search, true, InputType.TYPE_CLASS_TEXT)
        searchBox.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
        searchBox.setPadding(Ui.dp(this, 14f), Ui.dp(this, 9f), Ui.dp(this, 14f), Ui.dp(this, 9f))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(Ui.dp(this, 16f), Ui.dp(this, 2f), Ui.dp(this, 16f), Ui.dp(this, 6f))
        searchBox.layoutParams = lp
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
            override fun afterTextChanged(s: Editable?) {
                search = s?.toString() ?: ""
                refreshList()
            }
        })
        col.addView(searchBox)

        val countRow = Ui.row(this)
        countRow.setPadding(Ui.dp(this, 16f), Ui.dp(this, 2f), Ui.dp(this, 14f), Ui.dp(this, 4f))
        val countTv = Ui.hint(this, "${filtered().size} 条笔记", pal, 12f)
        countRow.addView(countTv)
        countRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val newBtn = Ui.button(this, "＋ 新建", true, pal, true)
        newBtn.setOnClickListener { newNote() }
        countRow.addView(newBtn)
        col.addView(countRow)

        val listView = ListView(this)
        listView.divider = null
        listView.dividerHeight = 0
        listView.setPadding(Ui.dp(this, 8f), 0, Ui.dp(this, 8f), Ui.dp(this, 8f))
        listView.clipToPadding = false
        listView.adapter = NoteAdapter()
        listView.setOnItemClickListener { _: AdapterView<*>?, _: View?, position: Int, _: Long ->
            val n = filtered().getOrNull(position) ?: return@setOnItemClickListener
            openNote(n.id)
        }
        listView.setOnItemLongClickListener { _: AdapterView<*>?, view: View?, position: Int, _: Long ->
            val n = filtered().getOrNull(position) ?: return@setOnItemLongClickListener false
            showListMenu(view ?: listView, n)
            true
        }
        col.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        if (filtered().isEmpty()) {
            col.addView(emptyState(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            listView.visibility = View.GONE
        }
        return col
    }

    private fun emptyState(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER
        val icon = Ui.textView(this, "📄", 46f, pal.text3)
        icon.gravity = Gravity.CENTER
        val title = Ui.textView(this, if (notes.isEmpty()) "还没有笔记" else "选择一篇笔记", 17f, pal.text)
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        title.gravity = Gravity.CENTER
        title.setPadding(0, Ui.dp(this, 8f), 0, Ui.dp(this, 4f))
        val desc = Ui.hint(this, if (notes.isEmpty()) "支持完整 Markdown：标题、列表、表格、代码块、任务清单…" else "从上面的列表选一篇开始阅读，或新建一篇", pal, 12.5f)
        desc.gravity = Gravity.CENTER
        val btn = Ui.button(this, if (notes.isEmpty()) "写第一篇笔记" else "新建笔记", true, pal)
        btn.setOnClickListener { newNote() }
        val btnWrap = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, Ui.dp(this@MainActivity, 10f), 0, 0) }
        btnWrap.addView(btn)
        col.addView(icon)
        col.addView(title)
        col.addView(desc)
        col.addView(btnWrap)
        return col
    }

    private fun filtered(): List<Note> {
        val q = search.trim().lowercase()
        return if (q.isEmpty()) notes else notes.filter {
            it.displayTitle().lowercase().contains(q) || it.content.lowercase().contains(q)
        }
    }

    private fun refreshList() {
        notes = repo.all()
        if (screen == "list") renderScreen()
    }

    private inner class NoteAdapter : BaseAdapter() {
        override fun getCount(): Int = filtered().size
        override fun getItem(position: Int): Any? = filtered().getOrNull(position)
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val n = filtered()[position]
            val row = LinearLayout(this@MainActivity)
            row.orientation = LinearLayout.VERTICAL
            row.setPadding(Ui.dp(this@MainActivity, 14f), Ui.dp(this@MainActivity, 11f), Ui.dp(this@MainActivity, 14f), Ui.dp(this@MainActivity, 11f))
            row.background = Ui.rounded(pal.panel, 12, this@MainActivity, pal.border, 1)

            val top = Ui.row(this@MainActivity)
            val t = Ui.textView(this@MainActivity, n.displayTitle(), 14.5f, pal.text)
            t.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            t.maxLines = 1
            t.ellipsize = android.text.TextUtils.TruncateAt.END
            top.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val time = Ui.hint(this@MainActivity, fmtTime(n.updatedAt), pal, 11f)
            top.addView(time)
            row.addView(top)

            val ex = Ui.hint(this@MainActivity, n.excerpt().ifEmpty { "空白笔记" }, pal, 12.5f)
            ex.maxLines = 2
            ex.ellipsize = android.text.TextUtils.TruncateAt.END
            ex.setPadding(0, Ui.dp(this@MainActivity, 3f), 0, 0)
            row.addView(ex)

            if (n.pinned || n.renderMode) {
                val badges = LinearLayout(this@MainActivity)
                badges.orientation = LinearLayout.HORIZONTAL
                badges.setPadding(0, Ui.dp(this@MainActivity, 6f), 0, 0)
                if (n.pinned) badges.addView(Ui.badge(this@MainActivity, "置顶", pal.accent, pal.accentSoft))
                if (n.renderMode) {
                    val b = Ui.badge(this@MainActivity, "渲染查看", pal.accent, pal.accentSoft)
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    lp.leftMargin = Ui.dp(this@MainActivity, 6f)
                    badges.addView(b, lp)
                }
                row.addView(badges)
            }
            val wrap = FrameLayout(this@MainActivity)
            wrap.setPadding(Ui.dp(this@MainActivity, 4f), Ui.dp(this@MainActivity, 3f), Ui.dp(this@MainActivity, 4f), Ui.dp(this@MainActivity, 3f))
            wrap.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return wrap
        }
    }

    /* ---------------- 笔记页 ---------------- */
    private fun buildNoteScreen(): View {
        val note = current ?: return buildListScreen()
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        val head = Ui.row(this)
        head.setPadding(Ui.dp(this, 6f), Ui.dp(this, 8f), Ui.dp(this, 8f), 0)
        val back = Ui.iconButton(this, R.drawable.ic_back, pal.text2, 40, 22)
        back.setOnClickListener { showList() }
        head.addView(back)

        val title = Ui.editText(this, "无标题", pal, note.title, true)
        title.setBackgroundColor(Color.TRANSPARENT)
        title.background = null
        title.setPadding(0, 0, 0, 0)
        title.includeFontPadding = false
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        head.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val more = Ui.iconButton(this, R.drawable.ic_more, pal.text2, 40, 22)
        more.setOnClickListener { showNoteMenu(more) }
        head.addView(more)
        col.addView(head)

        val sub = Ui.row(this)
        sub.setPadding(Ui.dp(this, 16f), 0, Ui.dp(this, 8f), Ui.dp(this, 6f))
        metaView = Ui.hint(this, metaText(note), pal, 11f)
        sub.addView(metaView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val seg = Ui.row(this).apply {
            background = Ui.rounded(pal.bg2, 10, this@MainActivity)
            setPadding(Ui.dp(this@MainActivity, 3f), Ui.dp(this@MainActivity, 3f),
                Ui.dp(this@MainActivity, 3f), Ui.dp(this@MainActivity, 3f))
        }
        val editBtn = segButton("编辑", !previewing)
        val viewBtn = segButton("预览", previewing)
        editBtn.setOnClickListener { if (previewing) { previewing = false; renderScreen() } }
        viewBtn.setOnClickListener { if (!previewing) { saveNow(); previewing = true; renderScreen() } }
        seg.addView(editBtn)
        seg.addView(viewBtn)
        sub.addView(seg)
        col.addView(sub)
        col.addView(Ui.divider(this, pal))

        if (previewing) {
            val scroll = ScrollView(this)
            val body = LinearLayout(this)
            body.orientation = LinearLayout.VERTICAL
            body.setPadding(Ui.dp(this, 20f), Ui.dp(this, 12f), Ui.dp(this, 20f), Ui.dp(this, 40f))
            Preview.render(this, body, note.content, pal, Theme.fontSize(this).toFloat())
            scroll.addView(body)
            scroll.setOnScrollChangeListener { _, _, sy, _, _ -> pinCodeHeaders(body, sy) }
            scroll.post { pinCodeHeaders(body, 0) }
            col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            val ed = EditText(this)
            ed.setText(note.content)
            ed.hint = "用 Markdown 记录…"
            ed.setTextSize(TypedValue.COMPLEX_UNIT_SP, (Theme.fontSize(this) - 1).toFloat())
            ed.typeface = Typeface.MONOSPACE
            ed.setTextColor(pal.text)
            ed.setHintTextColor(pal.text3)
            ed.setBackgroundColor(Color.TRANSPARENT)
            ed.gravity = Gravity.TOP or Gravity.START
            ed.setPadding(Ui.dp(this, 16f), Ui.dp(this, 10f), Ui.dp(this, 16f), Ui.dp(this, 24f))
            ed.setLineSpacing(Ui.dp(this, 4f).toFloat(), 1.25f)
            ed.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            ed.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
                override fun afterTextChanged(s: Editable?) {
                    current?.content = s?.toString() ?: ""
                    if (!suppressSnap) pushSnapIfIdle()
                    lastText = s?.toString() ?: ""
                    lastSel = ed.selectionStart.coerceAtLeast(0)
                    scheduleSave()
                }
            })
            editor = ed
            lastText = note.content
            lastSel = 0
            col.addView(ed, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            col.addView(buildToolbar())
        }

        title.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
            override fun afterTextChanged(s: Editable?) {
                current?.title = s?.toString() ?: ""
                scheduleSave()
            }
        })
        titleInput = title
        return col
    }

    /** 滚动预览时，把当前经过的代码块工具栏吸附在顶部 */
    private fun pinCodeHeaders(body: LinearLayout, scrollY: Int) {
        for (i in 0 until body.childCount) {
            val card = body.getChildAt(i) as? ViewGroup ?: continue
            if (card.tag != "code-card" || card.childCount < 2) continue
            val head = card.getChildAt(0)
            val headH = head.height
            val cardH = card.height
            if (headH <= 0 || cardH <= headH) { head.translationY = 0f; continue }
            val delta = (scrollY - card.top).coerceIn(0, cardH - headH)
            head.translationY = delta.toFloat()
            head.elevation = if (delta > 0) Ui.dp(this, 2f).toFloat() else 0f
        }
    }

    /* ---------------- 编辑快捷工具栏 ---------------- */
    private fun buildToolbar(): View {
        val scroll = HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        scroll.setBackgroundColor(pal.bg2)
        toolScroll = scroll
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(Ui.dp(this, 6f), Ui.dp(this, 4f), Ui.dp(this, 6f), Ui.dp(this, 4f))

        fun sep() {
            val v = View(this)
            v.setBackgroundColor(pal.border)
            val lp = LinearLayout.LayoutParams(Ui.dp(this, 1f), Ui.dp(this, 20f))
            lp.leftMargin = Ui.dp(this, 6f)
            lp.rightMargin = Ui.dp(this, 6f)
            row.addView(v, lp)
        }

        fun btn(label: String, bold: Boolean = false, size: Float = 14f, action: () -> Unit) {
            val tv = Ui.textView(this, label, size, pal.text2)
            tv.gravity = Gravity.CENTER
            if (bold) tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            tv.setPadding(Ui.dp(this, 9f), Ui.dp(this, 6f), Ui.dp(this, 9f), Ui.dp(this, 6f))
            tv.background = Ui.ripple(Ui.rounded(Color.TRANSPARENT, 8, this), Theme.alpha(pal.text, 0.08f))
            tv.isClickable = true
            tv.setOnClickListener { action() }
            row.addView(tv)
        }

        btn("↶") { undoEdit() }
        btn("↷") { redoEdit() }
        sep()
        btn("H1", true, 13f) { prefixLines("# ") }
        btn("H2", true, 13f) { prefixLines("## ") }
        sep()
        btn("B", true) { wrapSelection("**", "**") }
        btn("I", true) { wrapSelection("*", "*") }
        btn("S", true) { wrapSelection("~~", "~~") }
        sep()
        btn("•") { prefixLines("- ") }
        btn("1.") { prefixLines("1. ") }
        btn("☑") { prefixLines("- [ ] ") }
        btn("❝") { prefixLines("> ") }
        sep()
        btn("`") { wrapSelection("`", "`") }
        btn("</>", true, 12f) { wrapSelection("\n```\n", "\n```\n") }
        sep()
        btn("🔗") { wrapSelection("[", "](https://)") }
        btn("🖼") { pickImage() }
        btn("▦") { insertBlock("\n| 列1 | 列2 |\n| --- | --- |\n|  |  |\n") }
        btn("—") { insertBlock("\n---\n") }

        scroll.addView(row, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return scroll
    }

    private fun pushSnapIfIdle() {
        val now = System.currentTimeMillis()
        if (now - lastSnap < 700) return
        undoStack.addLast(lastText to lastSel)
        if (undoStack.size > 80) undoStack.removeFirst()
        redoStack.clear()
        lastSnap = now
    }

    private fun beforeEdit() {
        val ed = editor ?: return
        ed.requestFocus()
        undoStack.addLast(ed.text.toString() to ed.selectionStart.coerceAtLeast(0))
        if (undoStack.size > 80) undoStack.removeFirst()
        redoStack.clear()
        suppressSnap = true
    }

    private fun afterEdit(sel: Int) {
        editor?.requestFocus()
        editor?.setSelection(sel.coerceIn(0, editor?.text?.length ?: 0))
        editor?.bringPointIntoView(sel)
        current?.content = editor?.text?.toString() ?: ""
        lastText = editor?.text?.toString() ?: ""
        lastSel = sel
        suppressSnap = false
        scheduleSave()
    }

    private fun wrapSelection(prefix: String, suffix: String) {
        val ed = editor ?: return
        beforeEdit()
        val a = minOf(ed.selectionStart, ed.selectionEnd).coerceAtLeast(0)
        val b = maxOf(ed.selectionStart, ed.selectionEnd).coerceAtLeast(0)
        val selected = ed.text.substring(a, b)
        ed.text.replace(a, b, prefix + selected + suffix)
        val caret = if (selected.isEmpty()) a + prefix.length else a + prefix.length + selected.length + suffix.length
        afterEdit(caret)
    }

    private fun prefixLines(prefix: String) {
        val ed = editor ?: return
        beforeEdit()
        val text = ed.text.toString()
        val sel = ed.selectionStart.coerceAtLeast(0)
        val lineStart = text.lastIndexOf('\n', (sel - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        var lineEnd = text.indexOf('\n', sel).let { if (it < 0) text.length else it }
        if (lineEnd < lineStart) lineEnd = lineStart
        val block = text.substring(lineStart, lineEnd)
        val newBlock = block.split("\n").joinToString("\n") { line ->
            if (line.trim().isEmpty()) line else prefix + line
        }
        ed.text.replace(lineStart, lineEnd, newBlock)
        afterEdit(lineStart + newBlock.length)
    }

    private fun insertBlock(text: String) {
        val ed = editor ?: return
        beforeEdit()
        val at = ed.selectionStart.coerceAtLeast(0)
        ed.text.insert(at, text)
        afterEdit(at + text.length)
    }

    private fun undoEdit() {
        val ed = editor ?: return
        val prev = undoStack.removeLastOrNull() ?: run { Snack.show(this, "没有可撤销的操作"); return }
        redoStack.addLast(ed.text.toString() to ed.selectionStart.coerceAtLeast(0))
        suppressSnap = true
        ed.setText(prev.first)
        afterEdit(prev.second)
    }

    private fun redoEdit() {
        val ed = editor ?: return
        val next = redoStack.removeLastOrNull() ?: run { Snack.show(this, "没有可重做的操作"); return }
        undoStack.addLast(ed.text.toString() to ed.selectionStart.coerceAtLeast(0))
        suppressSnap = true
        ed.setText(next.first)
        afterEdit(next.second)
    }

    private fun segButton(label: String, on: Boolean): TextView {
        val tv = Ui.textView(this, label, 14f, if (on) pal.text else pal.text2)
        tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        tv.gravity = Gravity.CENTER
        tv.setPadding(Ui.dp(this, 16f), Ui.dp(this, 9f), Ui.dp(this, 16f), Ui.dp(this, 9f))
        tv.minWidth = Ui.dp(this, 62f)
        if (on) tv.background = Ui.rounded(pal.panel, 8, this, pal.border, 1)
        tv.isClickable = true
        return tv
    }

    private fun metaText(n: Note): String {
        val words = Md.plainText(n.content).length
        return "$words 字 · 保存于 ${fmtTime(n.updatedAt)}"
    }

    private fun iconText(s: String, size: Float): TextView {
        val tv = Ui.textView(this, s, size, pal.text2)
        tv.gravity = Gravity.CENTER
        val lp = LinearLayout.LayoutParams(Ui.dp(this, 38f), Ui.dp(this, 38f))
        tv.layoutParams = lp
        tv.isClickable = true
        tv.background = Ui.ripple(Ui.rounded(Color.TRANSPARENT, 10, this), Theme.alpha(pal.text, 0.06f))
        return tv
    }

    /* ---------------- 操作 ---------------- */
    private fun newNote() {
        saveNow()
        val now = System.currentTimeMillis()
        val n = Note(id = "", title = "", content = "", createdAt = now, updatedAt = now)
        current = repo.save(n)
        screen = "note"
        previewing = false
        notes = repo.all()
        renderScreen()
    }

    private fun openNote(id: String) {
        saveNow()
        val n = repo.get(id) ?: return
        current = n
        previewing = n.renderMode
        screen = "note"
        renderScreen()
    }

    private fun showList() {
        saveNow()
        screen = "list"
        current = null
        editor = null
        titleInput = null
        notes = repo.all()
        renderScreen()
    }

    private fun scheduleSave() {
        saveRunnable?.let { uiHandler.removeCallbacks(it) }
        val r = Runnable { saveNow() }
        saveRunnable = r
        uiHandler.postDelayed(r, 700)
        metaView?.text = current?.let { metaText(it) } ?: ""
    }

    private fun saveNow() {
        val n = current ?: return
        if (n.content.isEmpty() && n.title.isEmpty() && n.id.isEmpty()) return
        n.updatedAt = System.currentTimeMillis()
        repo.save(n)
        notes = repo.all()
        metaView?.text = metaText(n)
    }

    private fun toggleTheme() {
        Theme.setMode(this, if (pal.dark) "light" else "dark")
        rebuildTheme()
    }

    fun rebuildTheme() {
        pal = Theme.palette(this)
        Theme.applyWindow(this, pal)
        root.setBackgroundColor(pal.bg)
        renderScreen()
    }

    private fun openSettings() {
        SettingsDialog(this, object : SettingsDialog.Callback {
            override fun onThemeChanged() = rebuildTheme()
            override fun onNotesChanged() { refreshList() }
        }).show()
    }

    private fun showListMenu(anchor: View, n: Note) {
        MenuPopup(this, pal).show(anchor, listOf(
            MenuPopup.Item("打开") { openNote(n.id) },
            MenuPopup.Item(if (n.pinned) "取消置顶" else "置顶") {
                n.pinned = !n.pinned
                repo.save(n)
                notes = repo.all()
                refreshList()
                Snack.show(this, if (n.pinned) "已置顶" else "已取消置顶")
            },
            MenuPopup.Item(if (n.renderMode) "取消「渲染查看」" else "以渲染后的形式查看") {
                n.renderMode = !n.renderMode
                repo.save(n)
                notes = repo.all()
                refreshList()
                Snack.show(this, if (n.renderMode) "已设为打开时渲染查看" else "已设为打开时编辑")
            },
            MenuPopup.Item("重命名") { renameNote(n) },
            MenuPopup.Item("分享为图片") { shareAsImage(n) },
            MenuPopup.Item("删除笔记", danger = true) { deleteNote(n) }
        ))
    }

    private fun showNoteMenu(anchor: View) {
        val n = current ?: return
        MenuPopup(this, pal).show(anchor, listOf(
            MenuPopup.Item(if (n.renderMode) "取消「渲染查看」" else "以渲染后的形式查看") {
                n.renderMode = !n.renderMode
                repo.save(n)
                Snack.show(this, if (n.renderMode) "已设为打开时渲染查看" else "已设为打开时编辑")
            },
            MenuPopup.Item(if (n.pinned) "取消置顶" else "置顶") {
                n.pinned = !n.pinned
                repo.save(n)
                notes = repo.all()
                Snack.show(this, if (n.pinned) "已置顶" else "已取消置顶")
            },
            MenuPopup.Item("插入图片") { pickImage() },
            MenuPopup.Item("分享为图片") { shareAsImage(n) },
            MenuPopup.Item("重命名") { renameNote(n) },
            MenuPopup.Item("删除笔记", danger = true) { deleteNote(n) }
        ))
    }

    private fun renameNote(n: Note) {
        val field = Field(this, pal, "标题", n.title, "输入标题")
        DialogCard.show(this, pal, "重命名", field, listOf(
            DialogCard.Action("取消", false, false) { },
            DialogCard.Action("保存", true, false) {
                n.title = field.text().trim()
                n.updatedAt = System.currentTimeMillis()
                repo.save(n)
                notes = repo.all()
                if (current?.id == n.id) {
                    titleInput?.setText(n.title)
                    metaView?.text = metaText(n)
                } else {
                    refreshList()
                }
                Snack.show(this, "已重命名")
            }
        ))
    }

    private fun deleteNote(n: Note) {
        DialogCard.message(this, pal, "删除这篇笔记？", "「${n.displayTitle()}」将被永久删除，此操作不可撤销。", "删除", "取消", true) {
            val wasCurrent = current?.id == n.id
            repo.delete(n.id)
            if (wasCurrent) {
                current = null
                showList()
            } else {
                notes = repo.all()
                refreshList()
            }
            Snack.show(this, "已删除")
        }
    }

    private fun pickImage() {
        if (screen != "note" || current == null) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, REQ_IMAGE)
        } catch (e: Exception) {
            Snack.show(this, "无法打开图片选择器")
        }
    }

    private fun insertImage(rel: String) {
        if (current == null) return
        if (previewing) {
            previewing = false
            renderScreen()
        }
        val markdown = "\n![图片]($rel)\n"
        val ed = editor
        if (ed != null) {
            val start = ed.selectionStart.coerceAtLeast(0)
            ed.text.insert(start, markdown)
            ed.setSelection((start + markdown.length).coerceAtMost(ed.text.length))
        } else {
            current!!.content = current!!.content + markdown
            saveNow()
            renderScreen()
        }
        Snack.show(this, "已插入图片")
    }

    private fun shareAsImage(n: Note) {
        Snack.show(this, "正在生成图片…")
        Thread {
            try {
                val bmp = ShareImage.render(this, n, pal)
                runOnUiThread { SharePreview.show(this, pal, n, bmp) }
            } catch (e: Exception) {
                runOnUiThread { Snack.show(this, "生成图片失败：${e.message}") }
            }
        }.start()
    }

    /* ---------------- 生命周期 ---------------- */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SettingsDialog.REQ_IMPORT && resultCode == Activity.RESULT_OK && data?.data != null) {
            ImportBridge.handler?.invoke(data.data!!)
            ImportBridge.handler = null
        }
        if (requestCode == REQ_SAVE_CODE && resultCode == Activity.RESULT_OK && data?.data != null) {
            val text = CodeTools.pendingSave ?: ""
            try {
                contentResolver.openOutputStream(data.data!!)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                Snack.show(this, "代码已保存")
            } catch (e: Exception) {
                Snack.show(this, "保存失败：${e.message}")
            }
            CodeTools.pendingSave = null
        }
        if (requestCode == REQ_IMAGE && resultCode == Activity.RESULT_OK && data?.data != null) {
            val uri = data.data!!
            Thread {
                val rel = ImageStore.importUri(this, uri)
                runOnUiThread {
                    if (rel != null) insertImage(rel) else Snack.show(this, "导入图片失败")
                }
            }.start()
        }
    }

    override fun onPause() {
        saveNow()
        super.onPause()
    }

    override fun onBackPressed() {
        if (screen == "note") showList() else super.onBackPressed()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (Theme.mode(this) == "system") rebuildTheme()
    }

    companion object {
        const val REQ_IMAGE = 1002
        const val REQ_SAVE_CODE = 1003

        fun fmtTime(ts: Long): String {
            if (ts <= 0) return ""
            val now = System.currentTimeMillis()
            val diff = now - ts
            if (diff in 0..59999) return "刚刚"
            if (diff in 0..3599999) return "${diff / 60000} 分钟前"
            val d = java.util.Calendar.getInstance().apply { timeInMillis = ts }
            val n = java.util.Calendar.getInstance()
            val sameDay = d.get(java.util.Calendar.YEAR) == n.get(java.util.Calendar.YEAR) &&
                d.get(java.util.Calendar.DAY_OF_YEAR) == n.get(java.util.Calendar.DAY_OF_YEAR)
            val hm = String.format("%02d:%02d", d.get(java.util.Calendar.HOUR_OF_DAY), d.get(java.util.Calendar.MINUTE))
            if (sameDay) return hm
            return "${d.get(java.util.Calendar.MONTH) + 1}月${d.get(java.util.Calendar.DAY_OF_MONTH)}日"
        }
    }
}
