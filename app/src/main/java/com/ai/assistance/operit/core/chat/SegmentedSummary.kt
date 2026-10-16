package com.ai.assistance.operit.core.chat

import com.ai.assistance.operit.api.chat.EnhancedAIService
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.core.config.FunctionalPrompts
import com.ai.assistance.operit.core.config.SummaryFidelityPrompts
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.util.ChatMarkupRegex
import com.ai.assistance.operit.util.ChatUtils
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 私聊压缩改为分段累积：已结束的段落原样保留、不再重写；只把"悬着"的段和新消息交给总结模型，一次调用。
 * 每段带 chat_id 与起止消息时间戳，之后可按时间戳回原窗口读原文。
 */
internal object SegmentedSummary {
    const val HEADER = "【窗口段落】"
    const val END = "──段落完──"
    private const val KEEP_FULL = 12
    private val fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    private val rangeHeader = Regex("""^§\s*起#(\d+)\s*止#(\d+)\s*[｜|]\s*(.*)$""")
    private val indexLine = Regex("""^索引：chat_id=(\S+)\s+from=(\d+)\s+to=(\d+)""")

    data class Parsed(val closed: List<String>, val open: List<String>, val legacy: String?)

    fun time(ts: Long): String = fmt.format(Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()))

    fun isSegmented(summary: String?): Boolean = summary?.contains(HEADER) == true && summary.contains(END)

    fun parse(previous: String?): Parsed {
        if (previous.isNullOrBlank()) return Parsed(emptyList(), emptyList(), null)
        if (!isSegmented(previous)) return Parsed(emptyList(), emptyList(), previous.trim())
        val body = previous.substringAfter(HEADER).substringBefore(END)
        val blocks = mutableListOf<String>()
        val current = StringBuilder()
        body.lines().forEach { line ->
            if (line.startsWith("§")) {
                if (current.isNotBlank()) blocks += current.toString().trim()
                current.setLength(0)
            }
            if (blocks.isNotEmpty() || current.isNotEmpty() || line.startsWith("§")) current.appendLine(line)
        }
        if (current.isNotBlank()) blocks += current.toString().trim()
        val (open, closed) = blocks.partition { it.lineSequence().first().contains("悬着") }
        return Parsed(closed, open, null)
    }

    /** 送给总结模型的正文：去掉每条附带的设备快照，工具记录只留开头，编号旁标时间。 */
    fun source(messages: List<ChatMessage>, cleaned: List<Pair<String, String>>): String = buildString {
        cleaned.forEachIndexed { index, (role, content) ->
            val text = content.removePrefix("#${index + 1}: ")
                .replace(ChatMarkupRegex.attachmentTag, "")
                .replace(ChatMarkupRegex.toolOrToolResultBlock) { it.value.take(300) + "…" }
                .trim()
            if (text.isBlank()) return@forEachIndexed
            val who = if (role == "user") "她" else "我"
            append("#${index + 1} [${time(messages[index].timestamp)}] $who：$text\n\n")
        }
    }

    private fun prompt(open: List<String>, legacy: String?): String = """
        你在替这个窗口里的 AI 记分段笔记，用 AI 的第一人称"我"写，用户用原文名字或"她"。这是软件内部步骤，不要继续聊天，不调用工具。
        把下面的新消息按"一件事"切成段，每段格式严格如下（不加代码围栏，不写别的）：
        § 起#a 止#b｜已结束 或 悬着｜标题（20字内，直接说是什么事，比如"文字做爱""改压缩方案"）
        时间：MM-dd HH:mm 到 MM-dd HH:mm（悬着就写"MM-dd HH:mm 至今未结束"）
        内容：发生了什么、谁说了什么、关键原话（加引号）、定下的约定、没完成的事；值不值得回顾写一句。
        我的感受：只摘我原文里明确说出口的感受或心情，可以引原话；原文没说就写（无），不要替我猜。
        规则：
        - #a #b 是消息编号；时间照编号旁边的时间写。一段可以很短，不要把不相干的事硬凑一段。
        - 最后一段如果话题还在进行，标"悬着"；其他都标"已结束"。
        - ${if (open.isEmpty()) "没有上次悬着的段。" else "下面\"上次悬着的段\"如果在新消息里继续了，把它和新内容合成一段重写，起写 #0；如果它已经停了，原样照抄并改成已结束，起止也写 #0。"}
        - 只写新消息和悬着段里的内容，已结束的旧段落软件会自己保留，不要重复。
        ${if (legacy != null) "- \"旧摘要\"只是背景，帮助你理解前情，不要复述它。" else ""}
        ${open.takeIf { it.isNotEmpty() }?.joinToString("\n\n", prefix = "上次悬着的段：\n") ?: ""}
        ${legacy?.let { "旧摘要：\n${it.take(3000)}" } ?: ""}
    """.trimIndent() + "\n\n" + SummaryFidelityPrompts.ZH.trimIndent()

    suspend fun draft(
        service: EnhancedAIService,
        chatId: String,
        messages: List<ChatMessage>,
        cleaned: List<Pair<String, String>>,
        previous: String?
    ): String {
        val parsed = parse(previous)
        val output = service.callFunctionModel(
            FunctionType.SUMMARY,
            listOf(
                PromptTurn(kind = PromptTurnKind.SYSTEM, content = prompt(parsed.open, parsed.legacy)),
                PromptTurn(kind = PromptTurnKind.USER, content = "新消息：\n" + source(messages, cleaned))
            )
        )
        val text = ChatUtils.removeThinkingContent(output).trim()
        val segments = segments(text, chatId, messages, parsed.open)
        require(segments.isNotEmpty()) { "分段摘要格式不对，原上下文已保留。" }
        // 模型没接上悬着的段时，不能让它丢掉：原样保留，改成已结束
        val carried = if (parsed.open.isNotEmpty() && !Regex("""起#0\b""").containsMatchIn(text))
            parsed.open.map { it.replaceFirst("悬着", "已结束") } else emptyList()
        return render(parsed, carried + segments)
    }

    /** 把模型写的"起#a 止#b"换成真正的时间戳索引，模型不需要知道时间戳。 */
    fun segments(text: String, chatId: String, messages: List<ChatMessage>, open: List<String>): List<String> {
        val openFrom = open.firstNotNullOfOrNull { block ->
            block.lines().firstNotNullOfOrNull { indexLine.find(it.trim())?.groupValues?.get(2)?.toLongOrNull() }
        }
        val openTo = open.lastOrNull()?.lines()?.firstNotNullOfOrNull { indexLine.find(it.trim())?.groupValues?.get(3)?.toLongOrNull() }
        fun ts(n: Int, start: Boolean): Long? = when {
            n <= 0 -> if (start) openFrom else openTo
            else -> messages.getOrNull((n - 1).coerceAtMost(messages.lastIndex))?.timestamp
        }
        val blocks = mutableListOf<String>()
        var current: StringBuilder? = null
        text.lines().forEach { raw ->
            val line = raw.trimEnd()
            val match = rangeHeader.find(line.trim())
            if (match != null) {
                current?.let { blocks += it.toString().trim() }
                val (a, b, rest) = match.destructured
                val from = ts(a.toInt(), true) ?: messages.first().timestamp
                val to = ts(b.toInt(), false)?.coerceAtLeast(from) ?: messages.last().timestamp
                current = StringBuilder().appendLine("§ $rest").appendLine("索引：chat_id=$chatId from=$from to=$to")
            } else if (current != null && line.isNotBlank() && !line.startsWith("索引：")) {
                current!!.appendLine(line)
            }
        }
        current?.let { blocks += it.toString().trim() }
        return blocks
    }

    fun render(parsed: Parsed, fresh: List<String>): String {
        val all = parsed.closed + fresh
        val cut = (all.size - KEEP_FULL).coerceAtLeast(0)
        // 太老的段只留标题、时间、索引三行；原文还在，按索引能找回去
        val kept = all.mapIndexed { i, block ->
            if (i >= cut) block else block.lines().filter { it.startsWith("§") || it.startsWith("时间：") || it.startsWith("索引：") }.joinToString("\n")
        }
        return buildString {
            appendLine(FunctionalPrompts.SUMMARY_MARKER_CN)
            appendLine("$HEADER 这个窗口按时间分好的段落。要看某段原话：group_chat action=history，chat_id 填索引里的，after 填 from，就会从那条开始往后顺序读原文。")
            parsed.legacy?.let { appendLine("§ 已结束｜换成分段格式之前的旧摘要").appendLine(it.take(2400)).appendLine() }
            kept.forEach { appendLine(it).appendLine() }
            append(END)
        }.trim()
    }

    /** 给其他窗口做目录：每段只取标题、时间、索引三行。 */
    fun directory(summary: String): List<String> {
        if (!isSegmented(summary)) return emptyList()
        val p = parse(summary)
        return (p.closed + p.open).map { block ->
            block.lines().filter { it.startsWith("§") || it.startsWith("时间：") || it.startsWith("索引：") }.joinToString(" ")
        }
    }
}
