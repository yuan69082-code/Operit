package com.ai.assistance.operit.core.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * 旧日回声：聊天碰到某个主题时，把挂在这个主题下的旧东西列出标题。
 * 主题和挂哪些词由助手自己定（例如「性」下挂 做爱、dirty talk、高潮管理），
 * 软件只负责比对；打不打开由助手自己决定，所以只给标题不给正文。
 */
object EchoThemes {
    data class Theme(val name: String, val words: Set<String>)
    data class Candidate(val id: String, val title: String, val source: String, val tags: Set<String>, val body: String)

    private fun scope(role: String) = "echo-themes:$role"

    fun themes(context: Context, role: String): List<Theme> =
        CompanionStore(context).read(scope(role)).optJSONArray("themes").objects().map {
            Theme(it.getString("name"), it.optJSONArray("words").strings().toSet())
        }

    fun saveTheme(context: Context, role: String, name: String, words: Collection<String>): JSONObject {
        val cleanName = name.trim()
        val cleanWords = words.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        require(cleanName.length in 1..40) { "主题名须为1–40字" }
        require(cleanWords.size in 1..40 && cleanWords.all { it.length <= 40 }) { "主题下须挂1–40个词，每个不超过40字" }
        lateinit var saved: JSONObject
        CompanionStore(context).update(scope(role)) { data ->
            val list = data.optJSONArray("themes").objects().filter { it.getString("name") != cleanName }.toMutableList()
            require(list.size < 64) { "主题最多64个" }
            saved = JSONObject().put("name", cleanName).put("words", JSONArray(cleanWords))
            list.add(saved)
            data.put("themes", JSONArray(list))
        }
        return saved
    }

    fun deleteTheme(context: Context, role: String, name: String) {
        CompanionStore(context).update(scope(role)) { data ->
            data.put("themes", JSONArray(data.optJSONArray("themes").objects().filter { it.getString("name") != name.trim() }))
        }
    }

    /** 她这句话碰到了哪些主题：主题名或任一挂着的词出现在原话里。 */
    fun touched(themes: List<Theme>, spoken: String): List<Theme> {
        val text = spoken.lowercase()
        return themes.filter { theme -> (theme.words + theme.name).any { it.isNotBlank() && text.contains(it.lowercase()) } }
    }

    /**
     * 先按主题浮现（打了这个主题标签，或标题里带主题词），再补字面相关的。
     * 最多 [max] 条，返回标题、出处和浮现原因。
     */
    fun pick(candidates: List<Candidate>, spoken: String, themes: List<Theme>, max: Int = 5, random: Random = Random.Default): List<JSONObject> {
        val hit = touched(themes, spoken)
        val keys = CompanionPolicy.terms(spoken)
        val byTheme = linkedMapOf<String, Pair<Candidate, String>>()
        for (theme in hit) {
            val words = (theme.words + theme.name).map { it.lowercase() }
            candidates.filter { item ->
                theme.name in item.tags || item.tags.any { it.lowercase() in words } || words.any { item.title.lowercase().contains(it) }
            }.shuffled(random).forEach { byTheme.putIfAbsent(it.id, it to "主题：${theme.name}") }
        }
        val literal = if (keys.isEmpty()) emptyList() else candidates.filter { item ->
            item.id !in byTheme && (keys.intersect(CompanionPolicy.terms(item.title + " " + item.tags.joinToString(" "))).isNotEmpty() ||
                keys.intersect(CompanionPolicy.terms(item.body)).size >= 2)
        }.shuffled(random).map { it to "字面相关" }
        return (byTheme.values.toList() + literal).take(max).map { (item, why) ->
            JSONObject().put("id", item.id).put("title", item.title).put("from", item.source).put("why", why)
        }
    }

    /** 资料库、学习角读过的、日记本，三处的旧东西一起当候选。 */
    fun candidates(context: Context, scope: String, role: String?): List<Candidate> = buildList {
        CompanionStore(context).entries(scope).forEach {
            add(Candidate(it.getString("id"), it.getString("title"), "资料库/${it.optString("category")}",
                it.optJSONArray("tags").strings().toSet(), it.optString("content").take(2000)))
        }
        if (role.isNullOrBlank()) return@buildList
        runCatching { ReadingRoom(context, role).entries(history = true) }.getOrDefault(emptyList()).forEach {
            add(Candidate(it.getString("id"), it.optString("title"), "学习角已读",
                emptySet(), it.optString("summary").take(1000) + " " + it.optString("note")))
        }
        runCatching { AssistantRoom(context, role).diaries() }.getOrDefault(emptyList()).forEach {
            add(Candidate(it.getString("id"), "${it.optString("date")} ${it.optString("title")}", "日记本",
                emptySet(), it.optString("preview")))
        }
    }
}

internal fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }