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
            "annotate", "review" -> {
                store.annotate(bookId(), if (action == "annotate") data.getString("version") else null,
                    data.optInt("start"), data.optInt("end"), data.optString("quote"), data.optString("text"),
                    data.optString("font", "hand"), data.optString("color", "#486B65"), data.optJSONArray("lines") ?: JSONArray(),
                    data.optJSONArray("photos") ?: JSONArray(), role, data.getString("operation_id"))
                JSONObject().put("saved", true).put("user_unread", true)
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
}
