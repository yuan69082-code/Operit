package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.random.Random

/** Bounded metadata, never a second copy of the whole conversation in each prompt. */
fun CompanionStore.log(chatId: String, kind: String, detail: String) {
    update("activity:$chatId") { data ->
        val rows = data.optJSONArray("rows") ?: JSONArray()
        rows.put(JSONObject().put("at", System.currentTimeMillis()).put("kind", kind).put("detail", detail.take(1200)))
        while (rows.length() > 300) rows.remove(0)
        data.put("rows", rows)
    }
}

fun CompanionStore.logs(chatId: String): List<JSONObject> {
    val rows = read("activity:$chatId").optJSONArray("rows") ?: JSONArray()
    return (0 until rows.length()).map { rows.getJSONObject(it) }.asReversed()
}

fun CompanionStore.migrateMemory(chatId: String, scope: String) {
    val old = read("summary:$chatId")
    if (old.optBoolean("library_migrated")) return
    val pins = old.optJSONObject("pins") ?: JSONObject()
    val material = pins.keys().asSequence().map { it to pins.getString(it) }.toList() +
        listOf("旧摘要草稿" to old.optString("draft")).filter { it.second.isNotBlank() }
    update(scope) { data ->
        val entries = data.optJSONArray("library") ?: JSONArray()
        material.forEach { (title, text) ->
            text.chunked(12000).forEachIndexed { part, content ->
                val id = UUID.nameUUIDFromBytes("legacy:$chatId:$title:$part".toByteArray(Charsets.UTF_8)).toString()
                if ((0 until entries.length()).none { entries.getJSONObject(it).optString("id") == id }) {
                    entries.put(JSONObject().put("id", id).put("title", ("旧记忆：$title" + if (part > 0) " · ${part + 1}" else "").take(160))
                        .put("category", "旧保留记忆").put("kind", "knowledge").put("content", content)
                        .put("file", "").put("updated", System.currentTimeMillis()))
                }
            }
        }
        data.put("library", entries)
        if (material.isNotEmpty()) {
            val folders = data.optJSONArray("categories") ?: JSONArray()
            if ((0 until folders.length()).none { folders.getString(it) == "旧保留记忆" }) folders.put("旧保留记忆")
            data.put("categories", folders)
        }
    }
    update("summary:$chatId") { it.put("library_migrated", true) }
}

fun CompanionStore.todos(scope: String): List<JSONObject> {
    val rows = read(scope).optJSONArray("todos") ?: JSONArray()
    return (0 until rows.length()).map { rows.getJSONObject(it) }
}

fun CompanionStore.saveTodo(scope: String, id: String?, title: String, detail: String,
                            urgency: String, horizon: String, status: String): JSONObject {
    require(title.isNotBlank() && title.length <= 160 && detail.length <= 4000) { "待办标题最多160字，说明最多4000字。" }
    require(urgency in setOf("urgent", "normal") && horizon in setOf("short", "long") &&
        status in setOf("open", "done")) { "无效待办状态。" }
    val item = JSONObject().put("id", id ?: UUID.randomUUID().toString()).put("title", title)
        .put("detail", detail).put("urgency", urgency).put("horizon", horizon).put("status", status)
        .put("updated", System.currentTimeMillis())
    update(scope) { data ->
        val rows = data.optJSONArray("todos") ?: JSONArray()
        val index = (0 until rows.length()).firstOrNull { rows.getJSONObject(it).getString("id") == id }
        require(id == null || index != null) { "待办不存在。" }
        require(index != null || rows.length() < 256) { "待办最多256项，请先整理。" }
        if (index == null) rows.put(item) else rows.put(index, item)
        data.put("todos", rows)
    }
    return item
}

fun CompanionStore.finishTodo(scope: String, id: String, action: String, learning: String = "") {
    require(action in setOf("delete", "distill")) { "无效待办操作。" }
    update(scope) { data ->
        val rows = data.optJSONArray("todos") ?: JSONArray()
        val index = (0 until rows.length()).firstOrNull { rows.getJSONObject(it).getString("id") == id }
            ?: error("待办不存在。")
        val todo = rows.getJSONObject(index)
        if (action == "distill") {
            require(todo.getString("status") == "done") { "完成后才可沉淀。" }
            require(learning.isNotBlank() && learning.length <= 12000) { "请填写实际学到的内容，最多12000字。" }
            val entries = data.optJSONArray("library") ?: JSONArray()
            require(entries.length() < 512) { "资料库已满，请先整理。" }
            entries.put(JSONObject().put("id", UUID.randomUUID().toString()).put("title", todo.getString("title"))
                .put("category", "待办沉淀").put("kind", "knowledge").put("content", learning)
                .put("file", "").put("updated", System.currentTimeMillis()))
            data.put("library", entries)
            val categories = data.optJSONArray("categories") ?: JSONArray()
            if ((0 until categories.length()).none { categories.getString(it) == "待办沉淀" }) categories.put("待办沉淀")
            data.put("categories", categories)
        }
        rows.remove(index)
        data.put("todos", rows)
    }
}

object CompanionPolicy {
    // Chinese does not separate words with spaces. Match meaningful two-character units as
    // well as Latin words; require two matches to avoid random single-character retrieval.
    fun terms(text: String): Set<String> = buildSet {
        Regex("[A-Za-z0-9_]{3,}").findAll(text.lowercase()).forEach { add(it.value) }
        Regex("[\\p{IsHan}]{2,}").findAll(text).forEach { match ->
            match.value.windowed(2).filterNot { it in setOf("这个", "那个", "我们", "你们", "什么", "可以", "已经", "就是", "一下", "然后") }.forEach { add(it) }
        }
    }

    fun related(entries: List<JSONObject>, query: String, random: Random = Random.Default): List<JSONObject> {
        val keys = terms(query)
        if (keys.isEmpty()) return emptyList()
        return entries.filter { item ->
            val title = item.optString("title")
            val metadata = terms(title + " " + item.optString("category"))
            val body = terms(item.optString("content"))
            keys.intersect(metadata).isNotEmpty() || keys.intersect(body).size >= 2
        }.shuffled(random).take(3).map { item ->
            JSONObject().put("id", item.getString("id")).put("title", item.getString("title"))
                .put("category", item.getString("category")).put("preview", item.optString("content").take(180))
                .put("has_file", item.optString("file").isNotBlank())
        }
    }

    fun duplicate(text: String, previous: List<String>): Boolean {
        fun normalize(s: String) = s.lowercase().replace(Regex("[\\s\\p{P}\\p{S}]+"), "")
        val candidate = normalize(text)
        if (candidate.isEmpty()) return true
        return previous.any { old ->
            val normalized = normalize(old)
            candidate == normalized || (candidate.length >= 20 && normalized.contains(candidate)) ||
                (normalized.length >= 20 && candidate.contains(normalized) && candidate.length < normalized.length * 1.2)
        }
    }

    fun wakeAt(now: Long, minutes: Int): Long {
        require(minutes in 1..10080) { "下次唤醒须在1分钟至7天内。" }
        return now + minutes * 60_000L
    }
}
