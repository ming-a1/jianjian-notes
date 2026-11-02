package com.heyu.notes

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** SAF 选文件回调桥（MainActivity 转发） */
object ImportBridge {
    var handler: ((Uri) -> Unit)? = null
}

/** 设置弹窗：外观 / 备份 / 关于 三个 Tab，全部使用自绘控件 */
class SettingsDialog(private val act: MainActivity, private val cb: Callback) {

    interface Callback {
        fun onThemeChanged()
        fun onNotesChanged()
    }

    private val ctx: Context = act
    private var pal: Palette = Theme.palette(act)
    private var tab = 0
    private var dialog: Dialog? = null
    private var body: LinearLayout? = null

    private var davUrl: Field? = null
    private var davUser: Field? = null
    private var davPass: Field? = null
    private var davPath: Field? = null
    private var davStatus: TextView? = null
    private var keepField: Field? = null
    private var customField: Field? = null
    private var intervalSelect: Select? = null
    private var backupStatus: TextView? = null
    private var pwdStatus: TextView? = null
    private var pwdRow: LinearLayout? = null
    private var fontValue: TextView? = null
    private var scrollView: ScrollView? = null
    private var localBox: LinearLayout? = null
    private var cloudBox: LinearLayout? = null
    private val tabViews = ArrayList<TextView>()
    private var guard = false

    private val presets = listOf(1, 2, 3, 5, 10, 15, 30, 60, 180, 720, 1440)

    fun show() {
        pal = Theme.palette(act)
        dialog = Dialog(act)
        dialog!!.requestWindowFeature(Window.FEATURE_NO_TITLE)
        // 必须在 show 之前关掉窗口动画，否则系统仍会播放默认的缩放淡入
        dialog!!.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog!!.window?.setWindowAnimations(0)
        dialog!!.setOnDismissListener { saveDav() }
        renderRoot()
        dialog!!.show()
        val dm = ctx.resources.displayMetrics
        dialog!!.window?.setLayout((dm.widthPixels * 0.92f).toInt(), (dm.heightPixels * 0.84f).toInt())
        val decor = dialog!!.window?.decorView as? ViewGroup
        Overlay.push(decor)
        dialog!!.setOnDismissListener {
            Overlay.remove(decor)
            saveDav()
        }
    }

    private fun renderRoot() {
        val keepScroll = scrollView?.scrollY ?: 0
        tabViews.clear()
        pal = Theme.palette(act)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(pal.panel, 18, ctx)
        }
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(ctx, 14f), Ui.dp(ctx, 12f), Ui.dp(ctx, 8f), Ui.dp(ctx, 10f))
        }
        val tabs = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.rounded(pal.bg2, 11, ctx)
            setPadding(Ui.dp(ctx, 3f), Ui.dp(ctx, 3f), Ui.dp(ctx, 3f), Ui.dp(ctx, 3f))
        }
        listOf("外观", "备份", "关于").forEachIndexed { i, label ->
            val tv = Ui.textView(ctx, label, 13.5f, if (i == tab) pal.text else pal.text2)
            tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            tv.gravity = Gravity.CENTER
            tv.setPadding(Ui.dp(ctx, 16f), Ui.dp(ctx, 7f), Ui.dp(ctx, 16f), Ui.dp(ctx, 7f))
            tv.isClickable = true
            tv.setOnClickListener { switchTab(i) }
            tabs.addView(tv)
            tabViews.add(tv)
        }
        head.addView(tabs)
        applyTabStyles()
        head.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        val close = Ui.textView(ctx, "✕", 17f, pal.text3)
        close.gravity = Gravity.CENTER
        close.setPadding(Ui.dp(ctx, 12f), Ui.dp(ctx, 8f), Ui.dp(ctx, 12f), Ui.dp(ctx, 8f))
        close.isClickable = true
        close.setOnClickListener { dialog?.dismiss() }
        head.addView(close)
        root.addView(head)
        root.addView(Ui.divider(ctx, pal))

        val scroll = ScrollView(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 18f), Ui.dp(ctx, 6f), Ui.dp(ctx, 18f), Ui.dp(ctx, 24f))
        }
        body = col
        scrollView = scroll
        scroll.addView(col)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        dialog?.setContentView(root)
        // 先让弹窗出现，再填内容，避免打开时卡一下
        col.post { renderPanel(col) }
    }

    private fun switchTab(index: Int) {
        tab = index
        applyTabStyles()
        body?.let { renderPanel(it) }
        scrollView?.scrollTo(0, 0)
    }

    private fun applyTabStyles() {
        tabViews.forEachIndexed { i, tv ->
            val on = i == tab
            tv.setTextColor(if (on) pal.text else pal.text2)
            tv.background = if (on) Ui.rounded(pal.panel, 9, ctx, pal.border, 1) else null
        }
    }

    private fun renderPanel(col: LinearLayout) {
        col.removeAllViews()
        when (tab) {
            0 -> appearancePanel(col)
            1 -> backupPanel(col)
            else -> aboutPanel(col)
        }
    }

    /* ---------------- 外观 ---------------- */
    private fun appearancePanel(col: LinearLayout) {
        col.addView(Ui.sectionTitle(ctx, "主题", pal))
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val modes = listOf("light" to "浅色", "dark" to "深色", "system" to "跟随系统")
        modes.forEach { (value, label) ->
            val on = Theme.mode(ctx) == value
            val chip = Ui.textView(ctx, label, 13f, if (on) pal.accent else pal.text2)
            chip.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            chip.gravity = Gravity.CENTER
            chip.setPadding(Ui.dp(ctx, 16f), Ui.dp(ctx, 9f), Ui.dp(ctx, 16f), Ui.dp(ctx, 9f))
            chip.background = Ui.ripple(Ui.rounded(if (on) pal.accentSoft else pal.bg2, 10, ctx,
                if (on) pal.accent else pal.border, 1), Theme.alpha(pal.accent, 0.12f))
            chip.isClickable = true
            chip.setOnClickListener {
                Theme.setMode(ctx, value)
                cb.onThemeChanged()
                renderRoot()
                body?.let { renderPanel(it) }
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.rightMargin = Ui.dp(ctx, 8f)
            row.addView(chip, lp)
        }
        col.addView(row)
        col.addView(Ui.hint(ctx, "默认白色主题，可跟随系统", pal).apply { setPadding(0, Ui.dp(ctx, 8f), 0, 0) })

        col.addView(Ui.sectionTitle(ctx, "正文字号", pal))
        val sizeRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val minus = Ui.button(ctx, "A-", false, pal, true)
        val plus = Ui.button(ctx, "A+", false, pal, true)
        val value = Ui.textView(ctx, "${Theme.fontSize(ctx)} px", 14f, pal.text)
        value.gravity = Gravity.CENTER
        value.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        fontValue = value
        minus.setOnClickListener {
            Theme.setFontSize(ctx, (Theme.fontSize(ctx) - 1).coerceAtLeast(13))
            fontValue?.text = "${Theme.fontSize(ctx)} px"
        }
        plus.setOnClickListener {
            Theme.setFontSize(ctx, (Theme.fontSize(ctx) + 1).coerceAtMost(22))
            fontValue?.text = "${Theme.fontSize(ctx)} px"
        }
        sizeRow.addView(minus)
        sizeRow.addView(value, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sizeRow.addView(plus)
        col.addView(sizeRow)
    }

    /* ---------------- 备份 ---------------- */
    private fun backupPanel(col: LinearLayout) {
        col.addView(Ui.sectionTitle(ctx, "WebDAV", pal))
        col.addView(Ui.hint(ctx, "备份到坚果云、Nextcloud、群晖等网盘，同时保留一份在本机。", pal)
            .apply { setPadding(0, 0, 0, Ui.dp(ctx, 10f)) })

        val wd = AppPrefs.webdav(ctx)
        davUrl = Field(ctx, pal, "服务器地址", Json.str(wd as Map<String, Any>, "url", ""), "https://dav.jianguoyun.com/dav/")
        addField(col, davUrl!!)
        davUser = Field(ctx, pal, "账号", Json.str(wd as Map<String, Any>, "user", ""), "邮箱 / 用户名")
        addField(col, davUser!!)
        davPass = Field(ctx, pal, "密码 / 应用密码", Json.str(wd as Map<String, Any>, "pass", ""), "••••••",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        addField(col, davPass!!)
        davPath = Field(ctx, pal, "备份目录", Json.str(wd as Map<String, Any>, "path", "heyunotes"), "heyunotes")
        addField(col, davPath!!, 12)
        listOf(davUrl!!, davUser!!, davPass!!, davPath!!).forEach { f -> f.onFocusLost { saveDav() } }

        val testRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val testBtn = Ui.button(ctx, "测试连接", false, pal, true)
        testBtn.setOnClickListener { testDav() }
        davStatus = Ui.hint(ctx, "", pal)
        davStatus!!.setPadding(Ui.dp(ctx, 12f), 0, 0, 0)
        testRow.addView(testBtn)
        testRow.addView(davStatus)
        col.addView(testRow)

        col.addView(Ui.sectionTitle(ctx, "备份策略", pal))
        col.addView(Ui.hint(ctx, "两个开关默认关闭；开启后才会自动备份。", pal)
            .apply { setPadding(0, 0, 0, Ui.dp(ctx, 4f)) })
        toggleRow(col, "实时备份", "笔记一有修改就立即备份", AppPrefs.bool(ctx, "realtime", false)) { on, _ ->
            AppPrefs.setBackup(ctx, "realtime", on)
            BackupScheduler.apply(ctx)
        }
        toggleRow(col, "定时备份", "按固定间隔自动备份", AppPrefs.bool(ctx, "scheduled", false)) { on, _ ->
            AppPrefs.setBackup(ctx, "scheduled", on)
            BackupScheduler.apply(ctx)
        }

        col.addView(Ui.fieldLabel(ctx, "备份间隔", pal))
        val cur = AppPrefs.num(ctx, "interval", 60).toInt()
        val curIdx = presets.indexOf(cur)
        val labels = presets.map { p -> if (p < 60) "每 $p 分钟" else if (p < 1440) "每 ${p / 60} 小时" else "每天" } + "自定义…"
        val sel = Select(ctx, pal, labels, if (curIdx >= 0) curIdx else presets.size)
        intervalSelect = sel
        addField(col, sel, 8)
        sel.onSelect = { i ->
            if (i >= presets.size) {
                customField?.visibility = View.VISIBLE
                customField?.edit?.requestFocus()
            } else {
                customField?.visibility = View.GONE
                setInterval(presets[i])
            }
        }
        customField = Field(ctx, pal, "自定义间隔（分钟）", if (curIdx >= 0) "" else cur.toString(), "1 – 10080")
        customField!!.visibility = if (curIdx >= 0) View.GONE else View.VISIBLE
        customField!!.edit.inputType = InputType.TYPE_CLASS_NUMBER
        customField!!.onFocusLost {
            val v = customField!!.text().trim().toIntOrNull()
            if (v != null) setInterval(v.coerceIn(1, 10080))
        }
        addField(col, customField!!, 10)

        col.addView(Ui.sectionTitle(ctx, "安全与留存", pal))
        toggleRow(col, "加密备份文件", "AES-256-GCM，密码存放在系统 Keystore", AppPrefs.bool(ctx, "encrypt", false)) { on, toggle ->
            if (on && !SecureStore.hasBackupPassword(ctx)) {
                promptPassword("设置备份加密密码", true, onCancel = {
                    // 取消设置密码就回退开关，不能显示成已开启
                    toggle.setOn(false, false)
                    AppPrefs.setBackup(ctx, "encrypt", false)
                    updatePwdStatus()
                }) { pwd ->
                    try {
                        SecureStore.setBackupPassword(ctx, pwd)
                        AppPrefs.setBackup(ctx, "encrypt", true)
                        updatePwdStatus()
                        Snack.show(act, "已开启加密备份")
                    } catch (e: Exception) {
                        toggle.setOn(false, false)
                        AppPrefs.setBackup(ctx, "encrypt", false)
                        updatePwdStatus()
                        Snack.show(act, "保存密码失败：${e.message}")
                    }
                }
            } else {
                AppPrefs.setBackup(ctx, "encrypt", on)
                updatePwdStatus()
                if (on) Snack.show(act, "已开启加密备份")
            }
        }
        val pwdRowView = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, Ui.dp(ctx, 6f), 0, 0) }
        pwdRow = pwdRowView
        pwdStatus = Ui.hint(ctx, "", pal)
        pwdRowView.addView(pwdStatus, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val changePwd = Ui.button(ctx, "修改密码", false, pal, true)
        changePwd.setOnClickListener {
            promptPassword("修改备份密码", true) { pwd ->
                try {
                    SecureStore.setBackupPassword(ctx, pwd)
                    updatePwdStatus()
                    Snack.show(act, "备份密码已更新")
                } catch (e: Exception) {
                    Snack.show(act, "保存失败：${e.message}")
                }
            }
        }
        pwdRowView.addView(changePwd)
        col.addView(pwdRowView)
        updatePwdStatus()

        keepField = Field(ctx, pal, "保留备份份数", AppPrefs.num(ctx, "keep", 7).toString(), "7")
        keepField!!.edit.inputType = InputType.TYPE_CLASS_NUMBER
        keepField!!.onFocusLost {
            val v = keepField!!.text().trim().toIntOrNull() ?: 7
            AppPrefs.setBackup(ctx, "keep", v.coerceIn(1, 30))
            keepField!!.setText(AppPrefs.num(ctx, "keep", 7).toString())
        }
        addField(col, keepField!!, 10)

        col.addView(Ui.sectionTitle(ctx, "手动备份", pal))
        col.addView(Ui.hint(ctx, "本机备份存在应用私有目录；云端备份上传到 WebDAV。实时/定时备份会两边都写。", pal)
            .apply { setPadding(0, 0, 0, Ui.dp(ctx, 8f)) })
        val btnRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val localBtn = Ui.button(ctx, "备份到本机", true, pal)
        val cloudBtn = Ui.button(ctx, "备份到云端", false, pal)
        val lb = LinearLayout.LayoutParams(0, Ui.dp(ctx, 44f), 1f)
        lb.rightMargin = Ui.dp(ctx, 10f)
        btnRow.addView(localBtn, lb)
        btnRow.addView(cloudBtn, LinearLayout.LayoutParams(0, Ui.dp(ctx, 44f), 1f))
        localBtn.setOnClickListener { runBackup(false) }
        cloudBtn.setOnClickListener { runBackup(true) }
        col.addView(btnRow)

        val subRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val imp = Ui.button(ctx, "导入备份", false, pal, true)
        imp.setOnClickListener { pickImportFile() }
        val refresh = Ui.button(ctx, "刷新列表", false, pal, true)
        refresh.setOnClickListener { refreshBackupList() }
        val sb1 = LinearLayout.LayoutParams(0, Ui.dp(ctx, 34f), 1f)
        sb1.rightMargin = Ui.dp(ctx, 10f)
        subRow.addView(imp, sb1)
        subRow.addView(refresh, LinearLayout.LayoutParams(0, Ui.dp(ctx, 34f), 1f))
        val subWrap = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        subWrap.topMargin = Ui.dp(ctx, 10f)
        col.addView(subRow, subWrap)

        backupStatus = Ui.hint(ctx, "", pal).apply { setPadding(0, Ui.dp(ctx, 8f), 0, 0) }
        col.addView(backupStatus)

        col.addView(Ui.sectionTitle(ctx, "本机备份", pal))
        localBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(localBox)

        col.addView(Ui.sectionTitle(ctx, "云端备份", pal))
        cloudBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(cloudBox)

        refreshBackupList()
    }

    private fun addField(col: LinearLayout, v: View, bottom: Int = 10) {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = Ui.dp(ctx, bottom.toFloat())
        col.addView(v, lp)
    }

    private fun toggleRow(col: LinearLayout, title: String, sub: String, checked: Boolean, onChange: (Boolean, Toggle) -> Unit) {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(ctx, 9f), 0, Ui.dp(ctx, 9f))
        }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(Ui.textView(ctx, title, 14f, pal.text).apply { setTypeface(Typeface.DEFAULT, Typeface.BOLD) })
        texts.addView(Ui.hint(ctx, sub, pal))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val t = Toggle(ctx, pal, checked)
        t.listener = { on -> onChange(on, t) }
        row.addView(t)
        col.addView(row)
    }

    private fun updatePwdStatus() {
        val encryptOn = AppPrefs.bool(ctx, "encrypt", false)
        val has = SecureStore.hasBackupPassword(ctx)
        pwdStatus?.text = if (has) "已保存备份密码，导入时不再询问" else "尚未设置备份密码"
        pwdStatus?.setTextColor(pal.text3)
        // 没开加密备份时，不显示「修改密码」这一行
        pwdRow?.visibility = if (encryptOn) View.VISIBLE else View.GONE
    }

    private fun saveDav() {
        val u = davUrl ?: return
        AppPrefs.setWebdav(ctx, u.text().trim(), davUser!!.text().trim(), davPass!!.text(), davPath!!.text().trim())
    }

    private fun testDav() {
        saveDav()
        davStatus?.text = "测试中…"
        Thread {
            val res = BackupEngine(ctx).testWebdav()
            val ok = res["ok"] == true
            val msg = Json.str(res, "message", "")
            act.runOnUiThread {
                davStatus?.text = (if (ok) "✓ " else "✗ ") + msg
                davStatus?.setTextColor(if (ok) pal.accent else pal.danger)
            }
        }.start()
    }

    private fun setInterval(min: Int) {
        val v = min.coerceIn(1, 10080)
        if (v < 5 && !AppPrefs.bool(ctx, "realtime", false)) {
            val content = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.textView(ctx, "间隔小于 5 分钟比较耗电，系统也可能延迟执行。建议改用「实时备份」。", 13.5f, pal.text2))
            }
            DialogCard.show(act, pal, "建议使用实时备份", content, listOf(
                DialogCard.Action("取消", false, false) { renderRoot() },
                DialogCard.Action("仍然保存", false, false) {
                    AppPrefs.setBackup(ctx, "interval", v)
                    BackupScheduler.apply(ctx)
                },
                DialogCard.Action("改用实时备份", true, false) {
                    AppPrefs.setBackup(ctx, "realtime", true)
                    AppPrefs.setBackup(ctx, "scheduled", true)
                    AppPrefs.setBackup(ctx, "interval", 5)
                    BackupScheduler.apply(ctx)
                    renderRoot()
                }
            ))
            return
        }
        AppPrefs.setBackup(ctx, "interval", v)
        BackupScheduler.apply(ctx)
    }

    /* ---------------- 备份列表 ---------------- */
    private fun refreshBackupList() {
        val lb = localBox ?: return
        val cb = cloudBox ?: return
        lb.removeAllViews(); cb.removeAllViews()
        lb.addView(Ui.hint(ctx, "读取中…", pal))
        cb.addView(Ui.hint(ctx, "读取中…", pal))
        Thread {
            val items = BackupEngine(ctx).listBackups()
            act.runOnUiThread {
                lb.removeAllViews(); cb.removeAllViews()
                val locals = items.filter { Json.str(it, "source", "local") != "webdav" }
                val clouds = items.filter { Json.str(it, "source", "local") == "webdav" }
                if (locals.isEmpty()) {
                    lb.addView(Ui.hint(ctx, "还没有本机备份", pal))
                } else locals.forEach { lb.addView(backupRow(it)) }
                if (clouds.isEmpty()) {
                    cb.addView(Ui.hint(ctx, "还没有云端备份（未配置 WebDAV 时不会生成）", pal))
                } else clouds.forEach { cb.addView(backupRow(it)) }
            }
        }.start()
    }

    private fun backupRow(item: Map<String, Any?>): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(ctx, 6f), 0, Ui.dp(ctx, 6f))
        }
        val name = Json.str(item, "name", "")
        val type = Json.str(item, "type", "incremental")
        val enc = item["encrypted"] == true
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val t = Ui.textView(ctx, name, 12.5f, pal.text)
        t.maxLines = 1
        t.ellipsize = android.text.TextUtils.TruncateAt.END
        texts.addView(t)
        texts.addView(Ui.hint(ctx, (if (type == "full") "完整" else "增量") + " · " + (if (enc) "加密" else "明文"), pal, 11f))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val restore = Ui.button(ctx, "恢复", false, pal, true)
        restore.setOnClickListener { restoreItem(item) }
        val del = Ui.dangerButton(ctx, "删除", pal, true)
        del.setOnClickListener { confirmDeleteBackup(item) }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(ctx, 34f))
        lp.leftMargin = Ui.dp(ctx, 12f)
        row.addView(restore, lp)
        val lp2 = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(ctx, 34f))
        lp2.leftMargin = Ui.dp(ctx, 10f)
        row.addView(del, lp2)
        return row
    }

    private fun runBackup(cloud: Boolean) {
        saveDav()
        val wd = AppPrefs.webdav(ctx)
        val hasDav = Json.str(wd as Map<String, Any>, "url", "").trim().isNotEmpty()
        if (cloud && !hasDav) {
            Snack.show(act, "云端备份需要先在 WebDAV 里填写服务器地址")
            return
        }
        backupStatus?.text = if (cloud) "正在上传 WebDAV…" else "正在写入本机…"
        Thread {
            val res = BackupEngine(ctx).backupNow("manual", !cloud, cloud)
            val ok = res["ok"] == true
            val skipped = res["skipped"] == true
            val msg = Json.str(res, "message", Json.str(res, "error", ""))
            act.runOnUiThread {
                backupStatus?.text = if (ok) (if (skipped) msg else "✓ " + msg) else "备份失败：$msg"
                Snack.show(act, if (ok) (if (skipped) msg else (if (cloud) "已备份到云端" else "已备份到本机"))
                else "备份失败：$msg")
                refreshBackupList()
            }
        }.start()
    }

    private fun confirmDeleteBackup(item: Map<String, Any?>) {
        val name = Json.str(item as Map<String, Any>, "name", "")
        DialogCard.message(act, pal, "删除备份？", name, "删除", "取消", true) {
            Thread {
                BackupEngine(ctx).deleteBackup(Json.str(item as Map<String, Any>, "id", ""))
                act.runOnUiThread { refreshBackupList(); Snack.show(act, "已删除备份") }
            }.start()
        }
    }

    private fun restoreItem(item: Map<String, Any?>) {
        val id = Json.str(item as Map<String, Any>, "id", "")
        val name = Json.str(item as Map<String, Any>, "name", "")
        Thread {
            val bytes = try { BackupEngine(ctx).readBackup(id) } catch (e: Exception) { null }
            act.runOnUiThread { handleBytes(bytes, name) }
        }.start()
    }

    private fun pickImportFile() {
        saveDav()
        ImportBridge.handler = { uri -> onPickedFile(uri) }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            act.startActivityForResult(intent, REQ_IMPORT)
        } catch (e: Exception) {
            Snack.show(act, "无法打开文件选择器")
        }
    }

    private fun onPickedFile(uri: Uri) {
        Thread {
            val bytes = try {
                act.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (e: Exception) { null }
            act.runOnUiThread { handleBytes(bytes, uri.lastPathSegment ?: "backup.hnb") }
        }.start()
    }

    private fun handleBytes(bytes: ByteArray?, name: String) {
        if (bytes == null) { Snack.show(act, "读取备份失败"); return }
        if (bytes.size < 40) { Snack.show(act, "文件不是有效的备份"); return }
        if (!CryptoBox.isEncrypted(bytes)) {
            applyBackupText(CryptoBox.toText(bytes), name)
            return
        }
        val stored = SecureStore.getBackupPassword(ctx)
        if (stored.isNotEmpty()) {
            try {
                applyBackupText(CryptoBox.toText(CryptoBox.decrypt(bytes, stored)), name)
                return
            } catch (e: Exception) { }
        }
        promptPassword("备份已加密", false) { pwd ->
            Thread {
                try {
                    val text = CryptoBox.toText(CryptoBox.decrypt(bytes, pwd))
                    act.runOnUiThread { applyBackupText(text, name) }
                } catch (e: Exception) {
                    act.runOnUiThread { Snack.show(act, "密码错误或文件已损坏") }
                }
            }.start()
        }
    }

    private fun applyBackupText(text: String, name: String) {
        val doc = try { Json.obj(Json.parse(text)) } catch (e: Exception) { null }
        if (doc == null || Json.str(doc, "format", "") != "heyunotes.backup") {
            Snack.show(act, "不是「简笺」的备份文件")
            return
        }
        val count = Json.arr(doc["notes"]).size
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.textView(ctx, name, 13f, pal.text))
            addView(Ui.textView(ctx, "包含 $count 条笔记变更。合并会保留较新内容，覆盖会删除本机多余笔记。", 13f, pal.text2)
                .apply { setPadding(0, Ui.dp(ctx, 8f), 0, 0) })
        }
        DialogCard.show(act, pal, "导入备份", content, listOf(
            DialogCard.Action("取消", false, false) { },
            DialogCard.Action("合并导入", true, false) { mergeDoc(doc, false) },
            DialogCard.Action("覆盖导入", false, false) { mergeDoc(doc, true) }
        ))
    }

    private fun mergeDoc(doc: Map<String, Any?>, replaceAll: Boolean) {
        Thread {
            val repo = NotesRepo(ctx)
            val local = repo.all().associateBy { it.id }.toMutableMap()
            val incoming = Json.arr(doc["notes"]).map { Note.fromMap(Json.obj(it)) }
            var added = 0
            var updated = 0
            if (replaceAll && Json.str(doc as Map<String, Any>, "type", "") == "full") {
                val ids = incoming.map { it.id }.toSet()
                local.keys.filter { it !in ids }.forEach { repo.delete(it) }
            }
            incoming.forEach { n ->
                val cur = local[n.id]
                if (cur == null || n.updatedAt > cur.updatedAt) {
                    repo.save(n)
                    if (cur == null) added++ else updated++
                    local[n.id] = n
                }
            }
            var removed = 0
            Json.arr(doc["removed"]).forEach { raw ->
                val m = Json.obj(raw)
                val id = Json.str(m, "id", "")
                if (local.containsKey(id)) { repo.delete(id); local.remove(id); removed++ }
            }
            act.runOnUiThread {
                cb.onNotesChanged()
                refreshBackupList()
                Snack.show(act, "导入完成：新增 $added · 更新 $updated · 删除 $removed")
            }
        }.start()
    }

    /* ---------------- 关于 ---------------- */
    private fun aboutPanel(col: LinearLayout) {
        val repo = NotesRepo(ctx)
        col.addView(Ui.sectionTitle(ctx, "版本", pal))
        col.addView(Ui.textView(ctx, "简笺 v${BuildConfigVars.VERSION_NAME}", 14f, pal.text).apply {
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
        col.addView(Ui.hint(ctx, "原生 Kotlin 界面 · Android 8.0+", pal))

        col.addView(Ui.sectionTitle(ctx, "检查更新", pal))
        col.addView(Ui.hint(ctx, "从 Git 仓库读取最新版本，发现新版本会提示下载。", pal)
            .apply { setPadding(0, 0, 0, Ui.dp(ctx, 10f)) })
        val checkRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val checkBtn = Ui.button(ctx, "检查更新", true, pal, true)
        checkBtn.setOnClickListener { UpdateChecker.check(act, pal, true) }
        checkRow.addView(checkBtn)
        col.addView(checkRow)

        col.addView(Ui.sectionTitle(ctx, "存储", pal))
        val notes = repo.all().size
        val kb = repo.usedBytes() / 1024
        col.addView(Ui.textView(ctx, "$notes 条笔记 · $kb KB · 应用私有目录", 13f, pal.text2))

        col.addView(Ui.sectionTitle(ctx, "危险操作", pal))
        val wipe = Ui.dangerButton(ctx, "清空所有笔记", pal)
        wipe.setOnClickListener {
            DialogCard.message(act, pal, "清空所有笔记？", "共 $notes 条笔记将被永久删除，此操作不可撤销。", "全部删除", "取消", true) {
                NotesRepo(ctx).wipe()
                cb.onNotesChanged()
                renderRoot()
                Snack.show(act, "已清空")
            }
        }
        col.addView(wipe)
    }

    /* ---------------- 密码 ---------------- */
    private fun promptPassword(title: String, confirm: Boolean, onCancel: (() -> Unit)? = null, onOk: (String) -> Unit) {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val f1 = Field(ctx, pal, "密码（至少 6 位）", "", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val lp1 = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp1.bottomMargin = Ui.dp(ctx, 10f)
        box.addView(f1, lp1)
        val f2 = if (confirm) Field(ctx, pal, "再次输入", "", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) else null
        f2?.let { box.addView(it) }
        var dlgRef: Dialog? = null
        dlgRef = DialogCard.show(act, pal, title, box, listOf(
            DialogCard.Action("取消", false, false) { onCancel?.invoke() },
            DialogCard.Action("确定", true, false, dismiss = false) {
                val v1 = f1.text()
                when {
                    v1.length < 6 -> Snack.show(act, "密码至少 6 位")
                    confirm && f2 != null && f2.text() != v1 -> Snack.show(act, "两次输入不一致")
                    else -> { dlgRef?.dismiss(); onOk(v1) }
                }
            }
        ), cancelable = false)
    }

    companion object {
        const val REQ_IMPORT = 1001
    }
}
