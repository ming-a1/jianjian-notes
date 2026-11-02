package com.heyu.notes

import android.app.Activity
import android.content.Intent
import android.net.Uri

/** 检查更新：读取 Git 仓库的 version.json，比对 versionCode */
object UpdateChecker {

    fun check(act: Activity, pal: Palette, announceLatest: Boolean, onResult: ((Boolean) -> Unit)? = null) {
        Thread {
            var info = fetchJson(BuildConfigVars.UPDATE_URL)
            if (info == null) info = fetchJson(BuildConfigVars.UPDATE_URL_MIRROR)
            val data = info
            act.runOnUiThread {
                if (data == null) {
                    if (announceLatest) Snack.show(act, "检查失败：无法连接更新地址")
                    onResult?.invoke(false)
                    return@runOnUiThread
                }
                val code = Json.num(data, "code", 0L).toInt()
                val ver = Json.str(data, "version", "")
                if (code > BuildConfigVars.VERSION_CODE) {
                    showDialog(act, pal, ver, data)
                    onResult?.invoke(true)
                } else {
                    if (announceLatest) Snack.show(act, "已是最新版本 v${BuildConfigVars.VERSION_NAME}")
                    onResult?.invoke(false)
                }
            }
        }.start()
    }

    private fun showDialog(act: Activity, pal: Palette, ver: String, info: Map<String, Any>) {
        val link = resolveLink(BuildConfigVars.UPDATE_URL, Json.str(info, "url", ""))
        val notes = Json.str(info, "notes", "")
        val content = android.widget.LinearLayout(act).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(Ui.textView(act, "当前版本 v${BuildConfigVars.VERSION_NAME}  →  最新 v$ver", 13.5f, pal.text))
            if (notes.isNotBlank()) {
                addView(Ui.textView(act, notes, 12.5f, pal.text2)
                    .apply { setPadding(0, Ui.dp(act, 10f), 0, 0) })
            }
        }
        DialogCard.show(act, pal, "发现新版本", content, listOf(
            DialogCard.Action("取消", false, false) { },
            DialogCard.Action("更新", true, false) {
                try {
                    act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    Snack.show(act, "无法打开下载链接")
                }
            }
        ))
    }

    private fun resolveLink(base: String, link: String): String {
        if (link.startsWith("http://") || link.startsWith("https://")) return link
        val cut = base.lastIndexOf('/')
        val prefix = if (cut >= 0) base.substring(0, cut + 1) else base
        return prefix + link.removePrefix("/")
    }

    private fun fetchJson(url: String): Map<String, Any>? {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github.raw, application/json")
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            Json.obj(Json.parse(text))
        } catch (e: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (e: Exception) { }
        }
    }
}
