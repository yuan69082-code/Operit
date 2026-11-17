package com.ai.assistance.operit.core.companion

import org.json.JSONObject

/** Retrieval of adopted interpretations, not new experience, psychological scoring or dispatch. */
internal object SubjectRecall {
    val kinds = setOf("decision", "feedback", "union", "experience", "direction", "episode", "dream", "dream_process", "dream_understanding")
    data class Entry(val id: String, val localChat: String, val terms: Set<String>) {
        val weight: Long get() = id.length.toLong() + localChat.length + terms.sumOf { it.length.toLong() + 32 }
    }

    fun entry(row: JSONObject): Entry? {
        if (row.optBoolean("closed")) return null
        val content = if (row.getString("storage_kind") == "dream_process") row else row.getJSONObject("content")
        // Ended unions remain in history/rotation, never compete as a current association.
        if (row.getString("storage_kind") == "union" && content.getString("status") != "active") return null
        val text = listOf("meaning", "interpretation", "decision", "objects", "members", "effects", "intention_refs",
            "text", "scope", "uncertainty", "purpose", "goal", "resolve", "ambition", "related",
            "understanding", "category_ref", "goals", "importance", "long_term_impact", "supporting", "opposing",
            "title", "feeling", "keywords", "index_text", "design", "dream_review")
            .filter { content.has(it) }.joinToString(" ") { content.get(it).toString() } +
            if (row.getString("storage_kind") in setOf("dream", "dream_process", "dream_understanding"))
                " " + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date(row.getLong("at"))) else ""
        // Source snapshots and old quoted evidence must not make an unrelated interpretation match.
        return Entry(row.getString("id"), row.optString("local_chat"), InnerMaterialSelection.terms(text))
    }

    fun ranked(entries: Sequence<Entry>, input: String, chat: String, limit: Int): List<String> {
        require(limit in 1..8)
        val query = InnerMaterialSelection.terms(input)
        if (query.isEmpty()) return emptyList()
        val best = mutableListOf<Pair<String, Int>>()
        val order = compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first }
        entries.filter { it.localChat.isBlank() || it.localChat == chat }.forEach { entry ->
            val score = entry.terms.count { it in query }
            if (score > 0) {
                best += entry.id to score
                best.sortWith(order)
                if (best.size > limit) best.removeAt(best.lastIndex)
            }
        }
        return best.map { it.first }
    }

    /** Relevance and rotation share the existing archive. No new stored schema or history copy. */
    fun selected(data: JSONObject, kind: String, input: String, chat: String, cursor: Long,
                 rotation: List<JSONObject>? = null): List<JSONObject> {
        require(kind in kinds)
        val history = requireNotNull((data as? InternalStateDocument)?.history)
        val parent = if (kind in setOf("union", "episode", "dream_process")) null else "private"
        val relevant = history.relevantSubjectRows(kind, input, chat, 4)
        val rotating = rotation ?: run {
            val count = history.categoryCount(kind, parent)
            val offset = if (count == 0L) 0 else Math.floorMod(cursor, count).toInt()
            val first = history.categoryPage(kind, offset, 8, parent)
            first + if (offset > 0 && first.size < 8)
                history.categoryPage(kind, 0, minOf(offset, 8 - first.size), parent) else emptyList()
        }
        val rotatingVisible = if (kind in setOf("dream", "dream_process")) rotating.filterNot { DreamShelf.archived(it, System.currentTimeMillis()) } else rotating
        return (relevant + rotatingVisible).distinctBy { it.getString("id") }
            .filter { it.optString("local_chat").isBlank() || it.optString("local_chat") == chat }.take(8)
            .map { row -> JSONObject(row.toString()).apply {
                if (relevant.any { it.getString("id") == row.getString("id") }) put("selection_reason", "CURRENT_INPUT_RELEVANCE")
            } }
    }
}
