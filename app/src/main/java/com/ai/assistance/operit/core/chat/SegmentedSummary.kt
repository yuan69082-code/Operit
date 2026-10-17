package com.ai.assistance.operit.core.chat

import com.ai.assistance.operit.api.chat.EnhancedAIService
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.core.config.SummaryFidelityPrompts
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.util.ChatMarkupRegex
import com.ai.assistance.operit.util.ChatUtils
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 附加在原有压缩摘要后面的"窗口段落"。原摘要照旧生成；这里只额外记：
 * 每件事几点开始几点结束（没结束写悬着）、内容、值不值得回顾，以及 AI 聊天时自己记下的感受。
 * 已结束的段落原样保留不再重写；每段带 chat_id 与起止消息时间戳，可回原窗口读原文。
 */
internal object SegmentedSummary {
    const val HEADER = "【窗口段落】"
    const val END = "──段落完──"
    private const val KEEP_FULL = 12
    private val fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    private val rangeHeader = Regex("""^§\s*起#(\d+)\s*止#(\d+)\s*[｜|]\s*(.*)$""")
    private val indexLine = Regex("""^索引：chat_id=(\S+)\s+from=(\d+)\s+to=(\d+)""")

    data class Parsed(val closed: List<String>, val open: List<String>)
    data class Feeling(val at: Long, val text: String)

    fun time(ts: Long): String = fmt.format(Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()))

    fun hasBlock(summary: String?): Boolean = summary?.contains(HEADER) == true && summary.contains(END)

    /** 去掉段落块，剩下的是原来那份摘要，交给原有压缩流程继承。 */
    fun stripBlock(summary: String): String {
        if (!hasBlock(summary)) return summary
        val start = summary.indexOf(HEADER)
        val end = summary.indexOf(END, start) + END.length
        return (summary.substring(0, start) + summary.substring(end)).trim()
    }

    fun parse(previous: String?): Parsed {
        if (previous.isNullOrBlank() || !hasBlock(previous)) return Parsed(emptyList(), emptyList())
        val body = previous.substringAfter(HEADER).substringBefore(END)
        val blocks = mutableListOf<String>()
        var current: StringBuilder? = null
        body.lines().forEach { line ->
            if (line.startsWith("§")) {
                current?.let { if (it.isNotBlank()) blocks += it.toString().trim() }
                current = StringBuilder()
            }
            current?.appendLine(line)
        }
        current?.let { if (it.isNotBlank()) blocks += it.toString().trim() }
        val (open, closed) = blocks.partition { it.lineSequence().first().contains("悬着") }
        return Parsed(closed, open)
    }

    /** 送给模型的正文：去掉设备快照、记忆标签和思考，工具记录只留开头，编号旁标时间。 */
    fun source(messages: List<ChatMessage>): String = buildString {
        messages.forEachIndexed { index, message ->
            val text = ChatUtils.removeThinkingContent(message.content)
                .replace(Regex("<memory>.*?</memory>", RegexOption.DOT_MATCHES_ALL), "")
                .replace(ChatMarkupRegex.attachmentTag, "")
                .replace(ChatMarkupRegex.toolOrToolResultBlock) { it.value.take(200) + "…" }
                .trim()
            if (text.isBlank()) return@forEachIndexed
            val who = if (message.sender == "user") "她" else "我"
            append("#${index + 1} [${time(message.timestamp)}] $who：${text.take(4000)}\n\n")
        }
    }

    private fun prompt(open: List<String>): String = """
        你在替这个窗口里的 AI 记分段笔记，用 AI 的第一人称"我"写，用户用原文名字或"她"。这是软件内部步骤，不要继续聊天，不调用工具。
        把新消息按"一件事"切成段，每段格式严格如下（不加代码围栏，不写别的）：
        § 起#a 止#b｜已结束 或 悬着｜标题（20字内，直接说是什么事，比如"你和我文字做爱""改压缩方案"）
        时间：MM月dd日 HH:mm 到 HH:mm（还没结束就写"MM月dd日 HH:mm 开始，悬着未结束"）
        内容：大概什么内容、谁说了什么、关键原话（加引号）、约定、没做完的事。
        值不值得回顾：值得/一般/不必，加半句理由。
        规则：
        - #a #b 是消息编号，时间照编号旁边标的写。不相干的事不要硬凑一段，一段可以很短。
        - 最后一段如果话题还在进行就标"悬着"，其余标"已结束"。
        - 不写我的感受，那一栏由软件填我自己记下的原话。
        - ${if (open.isEmpty()) "没有上次悬着的段。" else "下面是上次悬着的段。新消息里它继续了：和新内容合成一段重写，起写 #0；它已经停了：照抄内容改成已结束，起止都写 #0，结束时间补上。"}
        - 已结束的旧段落软件会保留，不要重复。
        ${open.takeIf { it.isNotEmpty() }?.joinToString("\n\n", prefix = "上次悬着的段：\n") ?: ""}
    """.trimIndent() + "\n\n" + SummaryFidelityPrompts.ZH.trimIndent()

    suspend fun build(
        service: EnhancedAIService,
        chatId: String,
        messages: List<ChatMessage>,
        previous: String?,
        feelings: List<Feeling>
    ): String? {
        val parsed = parse(previous)
        val text = source(messages)
        if (text.isBlank()) return null
        val output = service.callFunctionModel(
            FunctionType.SUMMARY,
            listOf(
                PromptTurn(kind = PromptTurnKind.SYSTEM, content = prompt(parsed.open)),
                PromptTurn(kind = PromptTurnKind.USER, content = "新消息：\n$text")
            )
        )
        val raw = ChatUtils.removeThinkingContent(output).trim()
        val fresh = segments(raw, chatId, messages, parsed.open)
        if (fresh.isEmpty()) return null
        // 模型没接上悬着的段，就原样保住，标成已结束
        val carried = if (parsed.open.isNotEmpty() && !Regex("""起#0\b""").containsMatchIn(raw))
            parsed.open.map { it.replaceFirst("悬着", "已结束") } else emptyList()
        return render(parsed.closed + carried, withFeelings(fresh, feelings))
    }

    /** 把模型写的"起#a 止#b"换成真正的时间戳索引。 */
    fun segments(text: String, chatId: String, messages: List<ChatMessage>, open: List<String>): List<String> {
        fun idx(block: String?, group: Int) = block?.lines()?.firstNotNullOfOrNull { indexLine.find(it.trim())?.groupValues?.get(group)?.toLongOrNull() }
        val openFrom = idx(open.firstOrNull(), 2)
        val openTo = idx(open.lastOrNull(), 3)
        fun ts(n: Int, start: Boolean): Long? = if (n <= 0) (if (start) openFrom else openTo)
            else messages.getOrNull((n - 1).coerceAtMost(messages.lastIndex))?.timestamp
        val blocks = mutableListOf<String>()
        var current: StringBuilder? = null
        text.lines().forEach { raw ->
            val line = raw.trim()
            val match = rangeHeader.find(line)
            if (match != null) {
                current?.let { blocks += it.toString().trim() }
                val (a, b, rest) = match.destructured
                val from = ts(a.toInt(), true) ?: messages.first().timestamp
                val to = (ts(b.toInt(), false) ?: messages.last().timestamp).coerceAtLeast(from)
                current = StringBuilder().appendLine("§ $rest").appendLine("索引：chat_id=$chatId from=$from to=$to")
            } else if (current != null && line.isNotBlank() && !line.startsWith("索引：") && !line.startsWith("我的感受")) {
                current!!.appendLine(line)
            }
        }
        current?.let { blocks += it.toString().trim() }
        return blocks
    }

    /** 感受只用我聊天时自己记下的原话，按时间归到对应的段；没记就空着。 */
    fun withFeelings(blocks: List<String>, feelings: List<Feeling>): List<String> {
        if (blocks.isEmpty()) return blocks
        val starts = blocks.map { b -> b.lines().firstNotNullOfOrNull { indexLine.find(it)?.groupValues?.get(2)?.toLongOrNull() } ?: 0L }
        val first = starts.first()
        val grouped = feelings.filter { it.at >= first }.groupBy { f -> starts.indexOfLast { it <= f.at }.coerceAtLeast(0) }
        return blocks.mapIndexed { i, b ->
            val mine = grouped[i].orEmpty()
            b + "\n我的感受：" + if (mine.isEmpty()) "（我没记）" else mine.joinToString(" / ") { "[${time(it.at)}] ${it.text}" }
        }
    }

    fun render(kept: List<String>, fresh: List<String>): String {
        val all = kept + fresh
        val cut = (all.size - KEEP_FULL).coerceAtLeast(0)
        // 太老的段只留标题、时间、索引；原文还在，按索引能找回去
        val shown = all.mapIndexed { i, block -> if (i >= cut) block else brief(block).joinToString("\n") }
        return buildString {
            appendLine("$HEADER 这个窗口按事分好的段落。要看某段原话：group_chat action=history，chat_id 填索引里的，after 填 from，就从那条开始往后读原文。")
            shown.forEach { appendLine(it).appendLine() }
            append(END)
        }.trim()
    }

    private fun brief(block: String) = block.lines().filter { it.startsWith("§") || it.startsWith("时间：") || it.startsWith("索引：") }

    /** 给其他窗口做目录：每段一行。 */
    fun directory(summary: String): List<String> {
        val p = parse(summary)
        return (p.closed + p.open).map { brief(it).joinToString(" ") }
    }
}