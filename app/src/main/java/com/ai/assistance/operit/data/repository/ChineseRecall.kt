package com.ai.assistance.operit.data.repository

import java.util.Locale
import kotlin.math.ln

/** Scores only the caller's already-authorized corpus. Short names do not depend on trigrams. */
object ChineseRecall {
    data class Document(val id: Long, val title: String, val body: String)
    data class Match(val id: Long, val hits: Int, val score: Double)
    private val noise = setOf("什么", "那个", "这个", "之前", "上次", "后来", "时候", "一下", "我们", "你们", "可以", "怎么", "事情", "不是", "是我", "我说", "说的", "给你", "给我", "我又", "又不", "我再", "再看", "不行", "行不行", "好了", "哎呀", "肯定", "现在", "自己", "如果", "那你", "你是", "让我", "你让", "咋了", "删了", "了删")

    fun terms(query: String, segmented: List<String>, aliases: Map<String, String> = emptyMap()): List<String> {
        val result = linkedSetOf<String>()
        aliases.forEach { (alias, name) ->
            if (query.contains(alias, true) || query.contains(name, true)) { result.add(alias); result.add(name) }
        }
        Regex("[《“\"]([^》”\"]{2,48})[》”\"]").findAll(query).forEach { result.add(it.groupValues[1]) }
        result.addAll(segmented)
        // Do not turn every Chinese sentence into overlapping fragments such as 我又/又不.
        // Preserve short standalone names and common nickname forms missed by segmentation.
        Regex("[\\p{IsHan}]{2,}").findAll(query).filter { it.value.length == 2 }.forEach { result.add(it.value) }
        Regex("阿[\\p{IsHan}]").findAll(query).forEach { result.add(it.value) }
        val functionCharacters = "我你他她它的了是在不又再那这给让才会就也都把过吗呢呀么"
        Regex("[\\p{IsHan}]{2,}").findAll(query).forEach { run ->
            result.addAll(run.value.windowed(2).filter { pair -> pair.none { it in functionCharacters } })
        }
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
        val clues = terms.filter { it.isNotBlank() }.distinct()
        val starts = clues.flatMap { term ->
            Regex(Regex.escape(term), RegexOption.IGNORE_CASE).findAll(text).take(64).map { match ->
                (match.range.first - minOf(60, limit / 4)).coerceAtLeast(0).coerceAtMost(text.length - limit)
            }.toList()
        }
        val start = starts.distinct().maxByOrNull { offset ->
            val window = text.substring(offset, offset + limit)
            clues.filter { window.contains(it, ignoreCase = true) }.sumOf { it.length }
        } ?: 0
        return (if (start > 0) "…" else "") + text.substring(start, start + limit) + "…"
    }
}
