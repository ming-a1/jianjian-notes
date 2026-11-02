package com.heyu.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** 本地图片存储：复制到应用私有目录 images/，Markdown 里用相对路径 images/xxx.jpg 引用 */
object ImageStore {

    fun dir(ctx: Context): File {
        val d = File(ctx.filesDir, "images")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun importUri(ctx: Context, uri: Uri): String? {
        return try {
            val mime = ctx.contentResolver.getType(uri) ?: "image/jpeg"
            val ext = when {
                mime.contains("png") -> "png"
                mime.contains("webp") -> "webp"
                mime.contains("gif") -> "gif"
                mime.contains("bmp") -> "bmp"
                else -> "jpg"
            }
            val name = "img" + System.currentTimeMillis().toString(36) + UUID.randomUUID().toString().take(6) + "." + ext
            val input = ctx.contentResolver.openInputStream(uri) ?: return null
            input.use { ins ->
                FileOutputStream(File(dir(ctx), name)).use { out -> ins.copyTo(out) }
            }
            "images/$name"
        } catch (e: Exception) {
            null
        }
    }

    fun fileOf(ctx: Context, src: String): File? {
        var s = src.trim()
        if (s.startsWith("file://")) s = s.removePrefix("file://")
        if (s.startsWith("images/")) return File(dir(ctx), s.removePrefix("images/"))
        if (s.startsWith("/")) return File(s)
        return null
    }

    fun bitmap(ctx: Context, src: String, maxWidth: Int): Bitmap? {
        return try {
            val f = fileOf(ctx, src) ?: return null
            if (!f.exists()) return null
            val opts = BitmapFactory.Options()
            opts.inJustDecodeBounds = true
            BitmapFactory.decodeFile(f.absolutePath, opts)
            var sample = 1
            while (opts.outWidth / (sample * 2) >= maxWidth) sample *= 2
            opts.inJustDecodeBounds = false
            opts.inSampleSize = sample
            BitmapFactory.decodeFile(f.absolutePath, opts)
        } catch (e: Exception) {
            null
        }
    }
}
