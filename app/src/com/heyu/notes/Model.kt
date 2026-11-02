package com.heyu.notes

import android.content.Context

/** 笔记模型 + 存储仓库（底层复用 NoteStore 的 JSON 文件存储） */
data class Note(
    var id: String = "",
    var title: String = "",
    var content: String = "",
    var createdAt: Long = 0L,
    var updatedAt: Long = 0L,
    var pinned: Boolean = false,
    var renderMode: Boolean = false
) {
    fun displayTitle(): String = title.trim().ifEmpty { Md.title(content) }.ifEmpty { "无标题" }
    fun excerpt(): String = Md.plainText(content).let { if (it.length > 80) it.substring(0, 80) + "…" else it }

    fun toMap(): Map<String, Any> = linkedMapOf(
        "id" to id,
        "title" to title,
        "content" to content,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt,
        "pinned" to pinned,
        "renderMode" to renderMode
    )

    companion object {
        fun fromMap(m: Map<String, Any?>): Note = Note(
            id = Json.str(m as Map<String, Any>, "id", ""),
            title = Json.str(m as Map<String, Any>, "title", ""),
            content = Json.str(m as Map<String, Any>, "content", ""),
            createdAt = Json.num(m as Map<String, Any>, "createdAt", 0L),
            updatedAt = Json.num(m as Map<String, Any>, "updatedAt", 0L),
            pinned = Json.bool(m as Map<String, Any>, "pinned", false),
            renderMode = Json.bool(m as Map<String, Any>, "renderMode", false)
        )
    }
}

class NotesRepo(private val ctx: Context) {

    private val store = NoteStore(ctx)

    @Suppress("UNCHECKED_CAST")
    fun all(): List<Note> {
        val raw = store.list() as List<Map<String, Any>>
        return raw.map { Note.fromMap(it) }
            .sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updatedAt })
    }

    fun get(id: String): Note? = all().firstOrNull { it.id == id }

    fun save(note: Note): Note {
        val now = System.currentTimeMillis()
        if (note.id.isEmpty()) note.id = newId()
        if (note.createdAt == 0L) note.createdAt = now
        if (note.updatedAt == 0L) note.updatedAt = now
        store.save(note.toMap())
        return note
    }

    fun delete(id: String) {
        store.delete(id)
        AppSettings.addTombstone(ctx, id)
    }

    fun wipe() {
        store.clear()
        AppSettings.clearTombstones(ctx)
    }

    fun usedBytes(): Long = store.bytes()

    private fun newId(): String =
        "n" + System.currentTimeMillis().toString(36) + Integer.toString((Math.random() * 4096).toInt(), 36)
}
