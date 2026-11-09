package com.ai.assistance.operit.core.companion

/** Only explicit display markup is collected; provider reasoning and quoted examples are not notes.
 * A closed paragraph is durable even if a later tool, state report or response fails. */
internal class ActivityNoteStream(
    private val round: String,
    private val save: suspend (key: String, text: String, waking: Boolean) -> Unit,
    private val withdraw: suspend (key: String) -> Unit,
    private val privateChoice: suspend (key: String) -> Unit,
) {
    class SaveFailure(val failure: Exception) : kotlinx.coroutines.CancellationException("Activity note persistence failed")
    private suspend fun durable(block: suspend () -> Unit) {
        try { block() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { throw SaveFailure(failure) }
    }
    private data class State(val probe: String, val payload: String, val note: String?,
        val thinking: String?, val ticks: Int, val code: Int, val index: Int, val keys: List<String>)
    private val checkpoints = linkedMapOf<String, State>()
    private var probe = ""
    private val payload = StringBuilder()
    private var note: String? = null
    private var thinking: String? = null
    private var ticks = 0
    private var code = 0
    private var index = 0
    private val keys = mutableListOf<String>()
    private val opens = listOf("<activity_note>", "<waking_note>", "<activity_private/>", "<think>", "<thinking>")

    fun savepoint(id: String) {
        checkpoints[id] = State(probe, payload.toString(), note, thinking, ticks, code, index, keys.toList())
    }
    suspend fun rollback(id: String) {
        val old = checkpoints[id] ?: return
        keys.filter { it !in old.keys }.forEach { durable { withdraw(it) } }
        probe = old.probe; payload.clear(); payload.append(old.payload); note = old.note
        thinking = old.thinking; ticks = old.ticks; code = old.code; index = old.index
        keys.clear(); keys.addAll(old.keys)
        var after = false
        checkpoints.keys.toList().forEach { if (after) checkpoints.remove(it); if (it == id) after = true }
    }
    suspend fun append(chunk: String): String {
        val out = StringBuilder()
        for (ch in chunk) {
            if (note != null) {
                probe += ch
                val end = "</$note>"
                while (probe.isNotEmpty() && !end.startsWith(probe)) {
                    payload.append(probe.first()); probe = probe.drop(1)
                }
                if (probe == end) {
                    val text = payload.toString()
                    val key = "$round:note:${index++}:" + InnerStructure.hash(text)
                    if (text.isNotBlank()) { durable { save(key, text, note == "waking_note") }; keys.add(key) }
                    payload.clear(); probe = ""; note = null
                }
                continue
            }
            if (thinking != null) {
                probe += ch
                val end = "</$thinking>"
                while (probe.isNotEmpty() && !end.startsWith(probe)) {
                    out.append(probe.first()); probe = probe.drop(1)
                }
                if (probe == end) { out.append(probe); probe = ""; thinking = null }
                continue
            }
            if (ch == '`') {
                if (probe.isNotEmpty()) { out.append(probe); probe = "" }
                ticks++; out.append(ch); continue
            }
            if (ticks > 0) {
                if (code == 0) code = ticks else if (code == ticks || (code >= 3 && ticks >= code)) code = 0
                ticks = 0
            }
            if (code != 0) { out.append(ch); continue }
            probe += ch
            while (probe.isNotEmpty() && opens.none { it.startsWith(probe) }) {
                out.append(probe.first()); probe = probe.drop(1)
            }
            if (probe in opens) {
                when (probe) {
                    "<think>", "<thinking>" -> { thinking = probe.removePrefix("<").removeSuffix(">"); out.append(probe) }
                    "<activity_private/>" -> {
                        val key = "$round:private:${index++}"
                        durable { privateChoice(key) }; keys.add(key)
                    }
                    else -> note = probe.removePrefix("<").removeSuffix(">")
                }
                probe = ""
            }
        }
        return out.toString()
    }
    /** Incomplete note content is never substituted with an invented final paragraph. */
    fun finish(): String {
        val tail = if (note != null || (probe.startsWith("<") && opens.any { it.startsWith(probe) })) "" else probe
        probe = ""
        return tail
    }
}
