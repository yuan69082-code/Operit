package com.ai.assistance.operit.data.repository

import java.util.Locale
import kotlin.math.ln

/** Scores only the caller's already-authorized corpus. Short names do not depend on trigrams. */
object ChineseRecall {
    data class Document(val id: Long, val title: String, val body: String)
    data class Match(val id: Long, val hits: Int, val score: Double)
    private val noise = setOf("什么", "那个", "这个", "之前", "上次", "后来", "时候", "一下", "我们", "你们", "可以", "怎么", "事情")

    fun terms(query: String, segmented: List<String>, aliases: Map<String, String> = emptyMap()): List<String> {
        val result = linkedSetOf<String>()
        aliases.forEach { (alias, name) ->
            if (query.contains(alias, true) || query.contains(name, true)) { result.add(alias); result.add(name) }
        }
        Regex("[《“\"]([^》”\"]{2,48})[》”\"]").findAll(query).forEach { result.add(it.groupValues[1]) }
        result.addAll(segmented)
        // Bigrams supplement Jieba for previously unseen two-character names, without dropping the original terms.
        Regex("[\\p{IsHan}]{2,}").findAll(query).forEach { run -> result.addAll(run.value.windowed(2)) }
        return result.map { it.trim().lowercase(Locale.ROOT) }.filter { it.length in 2..48 && it !in noise }.distinct().take(64)
    }

    fun rank(documents: List<Document>, terms: List<String>): List<Match> {
        if (documents.isEmpty() || terms.isEmpty()) return emptyList()
        val patterns = terms.associateWith { token ->
            Regex(token.split('*').filter { it.isNotEmpty() }.joinToString(".*") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
        }
        val hits = documents.associate { doc -> doc.id to terms.filter { patterns.getValue(it).containsMatchIn(doc.title + "\n" + doc.body) } }
        val frequencies = terms.associateWith { term -> hits.values.count { term in it } }
        // Rare clues distinguish names and specific events from commonly repeated words.
        return documents.mapNotNull { doc ->
            val matched = hits.getValue(doc.id)
            if (matched.isEmpty()) null else Match(doc.id, matched.size, matched.sumOf { term ->
                val idf = ln(1.0 + documents.size.toDouble() / (1 + frequencies.getValue(term)))
                idf * (if (patterns.getValue(term).containsMatchIn(doc.title)) 2.0 else 1.0) * (1.0 + minOf(term.length, 12) / 12.0)
            })
        }.sortedWith(compareByDescending<Match> { it.score }.thenBy { it.id })
    }

    fun excerpt(text: String, terms: List<String>, limit: Int = 700): String {
        if (text.length <= limit) return text
        val hit = terms.map { text.indexOf(it, ignoreCase = true) }.filter { it >= 0 }.minOrNull() ?: 0
        val start = (hit - 100).coerceAtLeast(0).coerceAtMost(text.length - limit)
        return (if (start > 0) "…" else "") + text.substring(start, start + limit) + "…"
    }
}
