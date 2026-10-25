package com.ai.assistance.operit.ui.features.chat.voice

import android.content.Context
import android.util.AtomicFile
import com.ai.assistance.operit.data.model.ChatMessageTimestampAllocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/** Separate from model history: unfinished audio must never become an AI instruction. */
class VoiceMessageOutbox(context: Context) {
    data class Item(
        val id: Long, val chatId: String, val path: String,
        val quiet: List<Pair<Long, Long>>, val durationMillis: Long,
        val archive: String = "", val text: String = "", val acoustic: String = "",
        val status: String = "发送中 · 已保存", val failed: Boolean = false,
        val completed: Int = 0, val total: Int = 0,
        val analysisFailed: Boolean = false, val sendWithoutAnalysis: Boolean = false,
        val interrupted: Boolean = false,
    )
    private val directory = File(context.filesDir, "voice_outbox").apply { mkdirs() }
    private val state = MutableStateFlow<List<Item>>(emptyList())
    val items = state.asStateFlow()
    init {
        state.value = directory.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull { file ->
            try {
                val json = JSONObject(String(AtomicFile(file).readFully(), Charsets.UTF_8))
                val intervals = json.getJSONArray("quiet")
                Item(json.getLong("id"), json.getString("chat"), json.getString("path"),
                    (0 until intervals.length()).map { intervals.getJSONArray(it).let { row -> row.getLong(0) to row.getLong(1) } },
                    json.getLong("duration"), json.getString("archive"), json.getString("text"), json.optString("acoustic"),
                    status = when {
                        json.optBoolean("interrupted") -> "录音中断，已保存 · 可发送或重录"
                        json.optBoolean("analysisFailed") && !json.optBoolean("sendWithoutAnalysis") ->
                            "本地声学分析失败 · 原模型转写和录音已保存"
                        else -> "发送中断 · 录音已保存，点按重试"
                    },
                    failed = true, analysisFailed = json.optBoolean("analysisFailed"),
                    sendWithoutAnalysis = json.optBoolean("sendWithoutAnalysis"),
                    interrupted = json.optBoolean("interrupted"))
            } catch (error: Exception) {
                com.ai.assistance.operit.util.AppLogger.e("VoiceOutbox", "Cannot read $file; retained for recovery", error)
                null
            }
        }.sortedBy { it.id }
        state.value.forEach { ChatMessageTimestampAllocator.observe(it.id) }
    }
    @Synchronized fun add(file: File, quiet: List<Pair<Long, Long>>, chatId: String, interrupted: Boolean = false): Item {
        val item = Item(ChatMessageTimestampAllocator.next(), chatId, file.absolutePath, quiet,
            ((file.length() - 44).coerceAtLeast(0) * 1000 / 32000),
            status = if (interrupted) "录音中断，已保存 · 可发送或重录" else "发送中 · 已保存",
            failed = interrupted, interrupted = interrupted)
        update(item)
        return item
    }
    @Synchronized fun update(item: Item) {
        val json = JSONObject().put("id", item.id).put("chat", item.chatId).put("path", item.path)
            .put("quiet", org.json.JSONArray().apply { item.quiet.forEach { (a, b) -> put(org.json.JSONArray().put(a).put(b)) } })
            .put("duration", item.durationMillis).put("archive", item.archive).put("text", item.text).put("acoustic", item.acoustic)
        json.put("analysisFailed", item.analysisFailed).put("sendWithoutAnalysis", item.sendWithoutAnalysis)
            .put("interrupted", item.interrupted)
        val atomic = AtomicFile(File(directory, "${item.id}.json"))
        val output = atomic.startWrite()
        try { output.write(json.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (error: Throwable) { atomic.failWrite(output); throw error }
        state.value = (state.value.filterNot { it.id == item.id } + item).sortedBy { it.id }
    }
    /** Delete only after the user explicitly chooses to record again. */
    @Synchronized fun discardInterrupted(id: Long) {
        val item = state.value.firstOrNull { it.id == id && it.interrupted } ?: return
        val file = File(item.path)
        AtomicFile(File(directory, "$id.json")).delete()
        state.value = state.value.filterNot { it.id == id }
        file.delete()
    }
    @Synchronized fun sent(id: Long) {
        AtomicFile(File(directory, "$id.json")).delete()
        state.value = state.value.filterNot { it.id == id }
    }
}
