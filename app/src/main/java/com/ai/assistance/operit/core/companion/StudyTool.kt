package com.ai.assistance.operit.core.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal object StudyTool {
    fun execute(context: Context, role: String, action: String, id: String?, data: JSONObject, offset: Int): JSONObject {
        val store = StudyStore(context, role)
        fun bookId() = requireNotNull(id) { "缺少书目 id" }
        fun page(rows: List<JSONObject>, size: Int = 20) = JSONObject().put("total", rows.size).put("items", JSONArray(rows.drop(offset).take(size)))
            .put("next_offset", if (offset + size < rows.size) offset + size else JSONObject.NULL)
        return when (action) {
            "activity" -> store.recentActivity()
            "note" -> {
                val book = store.book(bookId())
                val note = book.getJSONArray("notes").objects().firstOrNull { it.getString("id") == data.getString("note_id") }
                    ?: error("此书目中不存在指定批注或回复")
                JSONObject(note.toString()).put("book", bookId()).put("saved", true)
            }
            "list" -> page(store.books().filter { (data.optString("section").isEmpty() || it.getString("section") == data.getString("section")) &&
                (data.optString("category").isEmpty() || it.getString("category") == data.getString("category")) }.map {
                JSONObject().put("id", it.getString("id")).put("title", it.getString("title")).put("section", it.getString("section")).put("category", it.getString("category"))
                    .put("chapters", it.getJSONArray("chapters").length()).put("ai_read", it.optJSONObject("ai_read")) })
            "get" -> store.book(bookId()).let { book -> JSONObject().put("id", bookId()).put("title", book.getString("title"))
                .put("section", book.getString("section")).put("category", book.getString("category")).put("source", book.getString("source"))
                .put("chapters", page(book.getJSONArray("chapters").objects())).put("ai_read", book.optJSONObject("ai_read")) }
            "create" -> store.create(data.getString("title"), data.getString("section"), data.getString("category"), role, data.optString("source"))
            "read" -> store.readForAI(bookId(), data.getString("version"), offset)
            "notes" -> page(store.book(bookId()).getJSONArray("notes").objects().filter { !data.has("version") || it.optString("version") == data.getString("version") }.map {
                JSONObject(it.toString()).apply { remove("lines"); put("quote", it.getString("quote").take(500)); put("quote_preview_only", it.getString("quote").length > 500) }
            }, 3)
            "write" -> {
                val book = store.book(bookId()); check(book.getString("section") == "writing") { "共读原文不可被助手改写，请在写作区创作" }
                val saved = store.write(bookId(), data.optString("chapter").ifEmpty { null }, data.optString("expected_version").ifEmpty { null },
                    data.getString("title"), data.getString("text"), data.optJSONArray("photos") ?: JSONArray(), role, true)
                val chapters = saved.getJSONArray("chapters").objects()
                val chapter = if (data.has("chapter")) chapters.first { it.getString("id") == data.getString("chapter") } else chapters.last()
                JSONObject().put("saved", true).put("book", bookId()).put("chapter", chapter.getString("id")).put("version", chapter.getString("version"))
            }
            "reply" -> {
                val parent = store.book(bookId()).getJSONArray("notes").objects().firstOrNull { it.getString("id") == data.getString("reply_to") } ?: error("被回复的批注不存在")
                val saved = store.annotate(bookId(), if (parent.isNull("version")) null else parent.getString("version"),
                    parent.getInt("start"), parent.getInt("end"), parent.getString("quote"), data.optString("text"),
                    data.optString("font", "hand"), data.optString("color", "#486B65"), data.optJSONArray("lines") ?: JSONArray(),
                    data.optJSONArray("photos") ?: JSONArray(), role, data.getString("operation_id"), parent.getString("id"))
                receipt(saved, role, data.getString("operation_id"))
            }
            "annotate", "review" -> {
                val saved = store.annotate(bookId(), if (action == "annotate") data.getString("version") else null,
                    data.optInt("start"), data.optInt("end"), data.optString("quote"), data.optString("text"),
                    data.optString("font", "hand"), data.optString("color", "#486B65"), data.optJSONArray("lines") ?: JSONArray(),
                    data.optJSONArray("photos") ?: JSONArray(), role, data.getString("operation_id"))
                receipt(saved, role, data.getString("operation_id"))
            }
            "schedule" -> {
                val minutes = data.getInt("minutes"); require(minutes in 1..10080)
                val purpose = data.getString("purpose"); require(purpose.isNotBlank() && purpose.length <= 1000)
                store.schedule(bookId(), minutes, purpose)
                JSONObject().put("scheduled", true)
            }
            else -> error("未知共读/写作操作")
        }
    }

    private fun receipt(book: JSONObject, author: String, operation: String): JSONObject {
        val note = book.getJSONArray("notes").objects().single { it.getString("author") == author && it.getString("operation") == operation }
        val chapter = book.getJSONArray("chapters").objects().firstOrNull { c -> c.getString("version") == note.optString("version") ||
            c.getJSONArray("history").let { a -> (0 until a.length()).any { a.getString(it) == note.optString("version") } } }
        return JSONObject().put("saved", true).put("book", book.getString("id"))
            .put("chapter", chapter?.getString("id") ?: JSONObject.NULL).put("version", note.opt("version"))
            .put("reply_to", note.opt("reply_to")).put("reply_id", note.getString("id")).put("author", author)
            .put("operation_id", operation).put("saved_at", note.getLong("at")).put("book_unread_count", book.optInt("unread"))
    }
}
