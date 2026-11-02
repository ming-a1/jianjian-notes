package com.heyu.notes

import android.content.Context

/** 设置读写的小封装（底层是 AppSettings 的 JSON） */
object AppPrefs {

    @Suppress("UNCHECKED_CAST")
    fun all(ctx: Context): MutableMap<String, Any?> = AppSettings.load(ctx) as MutableMap<String, Any?>

    fun save(ctx: Context, map: MutableMap<String, Any?>) {
        @Suppress("UNCHECKED_CAST")
        AppSettings.save(ctx, map as Map<String, Any>)
    }

    fun backup(ctx: Context): MutableMap<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return (Json.obj(all(ctx)["backup"]) as MutableMap<String, Any?>).also {
            it.putIfAbsent("realtime", false)
            it.putIfAbsent("scheduled", false)
            it.putIfAbsent("interval", 60)
            it.putIfAbsent("encrypt", false)
            it.putIfAbsent("keep", 7)
        }
    }

    fun setBackup(ctx: Context, key: String, value: Any?) {
        val s = all(ctx)
        val b = backup(ctx)
        b[key] = value
        s["backup"] = b
        save(ctx, s)
    }

    fun webdav(ctx: Context): MutableMap<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return (Json.obj(all(ctx)["webdav"]) as MutableMap<String, Any?>).also {
            it.putIfAbsent("url", "")
            it.putIfAbsent("user", "")
            it.putIfAbsent("pass", "")
            it.putIfAbsent("path", "heyunotes")
        }
    }

    fun setWebdav(ctx: Context, url: String, user: String, pass: String, path: String) {
        val s = all(ctx)
        s["webdav"] = linkedMapOf<String, Any?>(
            "url" to url, "user" to user, "pass" to pass, "path" to path.ifEmpty { "heyunotes" }
        )
        save(ctx, s)
    }

    fun updateUrl(ctx: Context): String {
        val u = Json.str(all(ctx) as Map<String, Any>, "updateUrl", "")
        return if (u.isBlank()) BuildConfigVars.UPDATE_URL else u
    }

    fun setUpdateUrl(ctx: Context, url: String) {
        val s = all(ctx)
        s["updateUrl"] = url
        save(ctx, s)
    }

    fun bool(ctx: Context, key: String, def: Boolean): Boolean = Json.bool(backup(ctx), key, def)
    fun num(ctx: Context, key: String, def: Long): Long = Json.num(backup(ctx), key, def)
    fun str(ctx: Context, key: String, def: String): String = Json.str(backup(ctx), key, def)
}
