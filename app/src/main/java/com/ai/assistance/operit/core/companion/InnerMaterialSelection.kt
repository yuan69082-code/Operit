package com.ai.assistance.operit.core.companion

import org.json.JSONObject
import java.util.Locale

/** Local text retrieval only: relevance never changes an adopted record or psychological value. */
internal object InnerMaterialSelection {
    data class Entry(val id: String, val parent: String, val terms: Set<String>) {
        // Approximate retained string/set cost; oversized collections remain searchable without
        // retaining their entire index between requests.
        val cacheWeight: Long get() = id.length.toLong() + terms.sumOf { it.length.toLong() + 32 }
    }

    private val words = Regex("[a-z0-9_]+|[\\p{L}&&[^\\p{IsHan}]]+")
    private val han = Regex("[\\p{IsHan}]+")
    private val fields = listOf("kind", "description", "object_ref", "context", "exceptions",
        "aspect", "label", "understanding", "feeling", "direction", "current_state")

    fun terms(text: String): Set<String> {
        val lower = text.lowercase(Locale.ROOT)
        val result = words.findAll(lower).map { it.value }.filter { it.length > 1 }.toMutableSet()
        han.findAll(lower).forEach { match ->
            if (match.value.length == 1) result += match.value
            else match.value.windowed(2).forEach { result += it }
        }
        return result
    }

    fun entry(row: JSONObject): Entry? {
        val content = row.getJSONObject("content")
        if (content.getString("status") != "active") return null
        return Entry(row.getString("id"), InnerRows.parentOf(row, false),
            terms(fields.filter { content.has(it) && !content.isNull(it) }
                .joinToString(" ") { content.getString(it) }))
    }

    /** All entries can compete, but only the bounded winning IDs leave the selector. */
    fun ranked(entries: Sequence<Entry>, query: Set<String>, parent: String, limit: Int): List<String> {
        require(parent in setOf("identity", "other") && limit in 1..8)
        if (query.isEmpty()) return emptyList()
        val best = mutableListOf<Pair<String, Int>>()
        val order = compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first }
        entries.forEach { entry ->
            if (entry.parent == parent) {
                val score = entry.terms.count { it in query }
                if (score > 0) {
                    best += entry.id to score
                    best.sortWith(order)
                    if (best.size > limit) best.removeAt(best.lastIndex)
                }
            }
        }
        return best.map { it.first }
    }
}
