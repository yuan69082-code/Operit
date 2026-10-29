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
 * 已结束的事件保留精华；每段带 chat_id 与起止消息时间戳，可回原窗口读原文。
 */
internal object SegmentedSummary {
    const val HEADER = "【窗口段落】"
    const val END = "──段落完──"
    private const val KEEP_FULL = 12
    private val fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    private val rangeHeader = Regex("""^§\s*起#(\d+)\s*止#(\d+)(?:\s+续接=(\d+))?\s*[｜|]\s*(.*)$""")
    private val indexLine = Regex("""^索引：chat_id=(\S+)\s+from=(\d+)\s+to=(\d+)""")

    data class Parsed(val closed: List<String>, val open: List<String>)
    data class Feeling(val at: Long, val text: String)
    data class Index(val chatId: String, val from: Long, val to: Long)

    fun index(block: String): Index? = block.lineSequence().firstNotNullOfOrNull { line ->
        indexLine.find(line.trim())?.let { match ->
            Index(match.groupValues[1], match.groupValues[2].toLong(), match.groupValues[3].toLong())
        }
    }

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
        内容：只写事件精华，发生了什么、结果或未决点，最多80字、一两句。不要复述对话来回、堆原话或写详细分析；细节按索引回原文。
        值不值得回顾：值得/一般/不必，加半句理由，总共不超过25字。
        规则：
        - #a #b 是消息编号，时间照编号旁边标的写。不相干的事不要硬凑一段，一段可以很短。
        - 是否结束以实际进展为准；暂停或换话题不等于解决，标题状态与时间栏必须一致。
        - 不写我的感受，那一栏由软件填我自己记下的原话。
        - ${if (open.isEmpty()) "没有上次悬着的段。" else "下面是上次悬着的段。只有新消息推进了它，才更新该事件：起写 #0，止写本次消息编号，并在止#b后写 续接=该事件索引的from。没有进展就不要重复输出，软件会保留。只有原文确认解决或结束才改已结束；暂停不算结束。多个悬着事件分别续接，禁止合成一件。"}
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
        // 未被新消息接上的未决事件仍未解决，不能因沉默自动结束。
        val updated = fresh.mapNotNull { index(it)?.from }.toSet()
        val carried = parsed.open.filter { index(it)?.from !in updated }
        val through = messages.maxOf { maxOf(it.timestamp, it.completedAt) }
        val withCurrentFeelings = withFeelings(fresh, feelings.filter { it.at <= through }).map { block ->
            val old = parsed.open.firstOrNull { index(it)?.from == index(block)?.from }
            val previousFeeling = old?.lineSequence()?.firstOrNull { it.startsWith("我的感受：") }
            if (block.endsWith("我的感受：（我没记）") && previousFeeling != null)
                block.substringBeforeLast("我的感受：") + previousFeeling else block
        }
        return render(parsed.closed + carried, withCurrentFeelings)
    }

    /** 把模型写的"起#a 止#b"换成真正的时间戳索引。 */
    fun segments(text: String, chatId: String, messages: List<ChatMessage>, open: List<String>): List<String> {
        val blocks = mutableListOf<String>()
        var current: StringBuilder? = null
        text.lines().forEach { raw ->
            val line = raw.trim()
            val match = rangeHeader.find(line)
            if (match != null) {
                current?.let { blocks += it.toString().trim() }
                val (a, b, continuation, rest) = match.destructured
                val previous = if (a == "0") {
                    val selected = if (continuation.isNotBlank()) open.singleOrNull { index(it)?.from == continuation.toLong() }
                        else open.singleOrNull()
                    requireNotNull(selected?.let(::index)) { "续接必须指向一个明确的悬着事件" }
                } else null
                val from = previous?.from ?: requireNotNull(messages.getOrNull(a.toInt() - 1)?.timestamp) { "事件起点编号越界" }
                val to = if (b == "0") requireNotNull(previous?.to) { "事件终点缺失" }
                    else requireNotNull(messages.getOrNull(b.toInt() - 1)?.timestamp) { "事件终点编号越界" }
                require(to >= from) { "事件终点早于起点" }
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
        // 数据库存全部事件精华；发送时另作投影，不能把旧事件从存档丢掉。
        val shown = (kept + fresh).associateBy { block ->
            index(block)?.let { "${it.chatId}:${it.from}" } ?: block
        }.values.sortedBy { index(it)?.from ?: 0L }.map(::compact)
        return buildString {
            appendLine("$HEADER 事件精华。group_chat events 检索本窗口或跨窗口事件；history 用 chat_id、after=from、end=to 读原文，长消息用 history_message 续读。")
            shown.forEach { appendLine(it).appendLine() }
            append(END)
        }.trim()
    }

    private fun excerpt(text: String, limit: Int): String {
        val marker = "…〔节选，完整记录可回查〕"
        val clean = text.removeSuffix(marker)
        if (clean.length <= limit) return text
        val prefix = clean.take(limit)
        val stop = prefix.indexOfLast { it in "。！？；" }
        return (if (stop >= limit / 2) prefix.take(stop + 1) else prefix) + marker
    }

    fun compact(block: String): String {
        val lines = block.lines()
        val first = lines.first()
        val open = first.contains("悬着")
        return lines.mapNotNull { line -> when {
            line.startsWith("§") -> line
            line.startsWith("索引：") -> line
            line.startsWith("时间：") -> if (!open && line.contains("悬着")) {
                // 已结束事件的终点取实际索引，避免旧模板残留的“悬着”自相矛盾。
                val end = lines.firstNotNullOfOrNull { indexLine.find(it)?.groupValues?.get(3)?.toLongOrNull() }
                if (end != null) "时间：${line.substringAfter("时间：").substringBefore(" 开始")} 至 ${time(end)}" else line
            } else line
            line.startsWith("内容：") -> "内容：" + excerpt(line.substringAfter("内容："), 100)
            line.startsWith("值不值得回顾：") -> "值不值得回顾：" + excerpt(line.substringAfter("值不值得回顾："), 30)
            line.startsWith("我的感受：") -> "我的感受：" + excerpt(line.substringAfter("我的感受："), 80)
            else -> null
        } }.joinToString("\n")
    }

    /** 只收短实际模型输入，不修改数据库摘要或原聊天。 */
    fun forPrompt(summary: String): String {
        if (!hasBlock(summary)) return SummaryPromptBudget.project(summary, SummaryPromptBudget.MAX_CHARS)
        val parsed = parse(summary)
        val all = (parsed.closed + parsed.open).sortedBy { block ->
            index(block)?.to ?: 0L
        }
        val recent = all.takeLast(KEEP_FULL).toSet()
        val oldHeader = summary.substringAfter(HEADER).substringBefore('\n')
        val total = Regex("存档事件共(\\d+)件").find(oldHeader)?.groupValues?.get(1)?.toInt() ?: all.size
        fun display(blocks: List<String>) = render(emptyList(), blocks).replaceFirst(HEADER,
            "$HEADER 存档事件共${total}件，本次显示${blocks.size}件；其余用 group_chat events 查询，status=悬着 可查未决事项。未显示不代表已结束。")
        // Recent activity gets detail first (including old events that were just continued).
        // Unresolved events cannot bypass the total budget and grow every request forever.
        val prioritized = (all.takeLast(4).asReversed() + parsed.open.sortedByDescending { index(it)?.to ?: 0L } +
            all.filter { it in recent }.asReversed()).distinct()
        val selected = mutableListOf<String>()
        val abbreviated = mutableListOf<String>()
        for (block in prioritized) {
            val candidate = compact(block)
            if (display(selected + candidate).length <= SummaryPromptBudget.EVENT_CHARS - 600) selected += candidate
            else abbreviated += block
        }
        for (block in abbreviated) {
            // Keep complete identifiers; never cut an index into an unusable tool argument.
            val directoryEntry = brief(block).joinToString("\n")
            if (display(selected + directoryEntry).length <= SummaryPromptBudget.EVENT_CHARS) selected += directoryEntry
        }
        val replacement = display(selected)
        val body = SummaryPromptBudget.project(stripBlock(summary), SummaryPromptBudget.MAX_CHARS - replacement.length - 2)
        return if (body.isBlank()) replacement else "$body\n\n$replacement"
    }

    fun promptTurns(turns: List<PromptTurn>): List<PromptTurn> = turns.map { turn ->
        if (turn.kind == PromptTurnKind.SUMMARY) turn.copy(content = forPrompt(turn.content)) else turn
    }

    private fun brief(block: String) = block.lines().filter { it.startsWith("§") || it.startsWith("时间：") || it.startsWith("索引：") }

    /** 给其他窗口做目录：每段一行。 */
    fun directory(summary: String): List<String> {
        val p = parse(summary)
        return (p.closed + p.open).map { brief(it).joinToString(" ") }
    }
}
