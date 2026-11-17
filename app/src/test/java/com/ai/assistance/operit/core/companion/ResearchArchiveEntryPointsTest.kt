package com.ai.assistance.operit.core.companion

import android.app.Application
import android.content.Context
import com.ai.assistance.operit.core.tasks.TaskBoard
import com.ai.assistance.operit.core.tasks.TaskEvents
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.data.model.*
import com.ai.assistance.operit.data.preferences.AssistantScope
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Normal stores/tools build the data. Only the corrupt legacy fixture below edits an input file;
 * no test writes final SQLite state or supplies a psychological outcome. */
@RunWith(OperitStorageTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ResearchArchiveEntryPointsTest {
    private lateinit var context: Context
    private lateinit var role: String
    private lateinit var chat: String
    private lateinit var store: CompanionStore
    private val board get() = TaskBoard(context, "role:$role", false)
    private val reading get() = ReadingRoom(context, role)
    @Before fun setup(): Unit = runBlocking {
        context = RuntimeEnvironment.getApplication()
        role = CharacterCardManager.getInstance(context).createCharacterCard(CharacterCard("", "历史承接"))
        val window = ChatHistory(title = "研究入口", messages = emptyList(), characterCardId = role)
        ChatHistoryManager.getInstance(context).saveChatHistory(window); chat = window.id
        store = CompanionStore(context)
        CompanionTools.register(AIToolHandler.getInstance(context), context)
        RoomTools.register(AIToolHandler.getInstance(context), context)
    }
    private fun enable(value: Boolean = true) { store.preferences.edit().putBoolean("internal_state:$role", value).commit() }
    private fun task(title: String) = board.save(role, null, JSONObject().put("title", title), setOf(role)).getString("id")
    private fun call(name: String, vararg arguments: Pair<String, String>): JSONObject {
        val args = arguments.toMap() + mapOf("__operit_package_chat_id" to chat, "__operit_package_caller_card_id" to role)
        val result = AIToolHandler.getInstance(context).executeTool(AITool(name, args.map { ToolParameter(it.key, it.value) }))
        assertTrue(result.error, result.success)
        return JSONObject(result.result.toString())
    }
    private fun notes(id: String): List<JSONObject> {
        val result = mutableListOf<JSONObject>(); var offset = 0
        while (true) {
            val page = call("companion_todo", "action" to "get", "id" to id, "offset" to offset.toString())
            result += page.getJSONArray("history").objects()
            if (page.isNull("next_history_offset")) return result
            offset = page.getInt("next_history_offset")
        }
    }

    @Test fun migrationPreservesLegacyFactsThenKeepsMoreThanEightyNotesAndFullEdits() {
        val id = task("以前已在进行的研究")
        repeat(85) { board.comment(id, if (it % 2 == 0) "user" else role, "旧批注$it") }
        val original = store.read("role:$role").getJSONArray("todos").getJSONObject(0).toString()
        val version = board.get(id).getInt("version")
        enable()
        assertEquals(version, board.get(id).getInt("version"))
        repeat(25) { call("companion_todo", "action" to "comment", "id" to id, "content" to "新进度$it") }
        val full = "保留限定和例外".repeat(450)
        board.save("user", id, JSONObject().put("detail", full).put("origin", full), setOf(role))
        val history = notes(id)
        assertEquals(106, history.size) // 80 existing + 25 notes + one full revision
        assertTrue(history.any { it.optString("text") == "旧批注6" && it.optString("actor") == "user" })
        assertEquals(full, JSONObject(history.first().getString("text")).getString("origin"))
        assertEquals(full, board.get(id).getString("detail"))
        assertEquals(original, store.read("role:$role").getJSONArray("todos").getJSONObject(0).toString())
        enable(false)
        assertEquals(106, notes(id).size) // disabling/reopening cannot revive the legacy copy
        assertEquals(full, TaskBoard(context, "role:$role", false).get(id).getString("origin"))
    }

    @Test fun pagesBeyondOldCapsRemainReachableAndRelevantProgressEntersNormalRequest(): Unit = runBlocking {
        enable()
        val target = task("spectrometer calibration")
        repeat(320) { task("普通事项$it") }
        val ids = mutableSetOf<String>(); var offset = 0
        while (true) {
            val page = call("companion_todo", "action" to "list", "offset" to offset.toString())
            assertEquals(321, page.getInt("total")); assertTrue(page.getJSONArray("todos").length() <= 20)
            ids += page.getJSONArray("todos").objects().map { it.getString("id") }
            if (page.isNull("next_offset")) break
            offset = page.getInt("next_offset")
        }
        assertEquals(321, ids.size)
        call("companion_todo", "action" to "submit", "id" to target)
        val pending = requireNotNull(board.pending())
        assertEquals(target, pending.getString("id"))
        val event = pending.getJSONObject("dispatch").getString("id")
        assertNotNull(board.claim(target, event)); assertNull(board.claim(target, event))
        TaskEvents.recover(board); assertNull(board.pending())
        assertEquals("interrupted", board.get(target).getJSONObject("dispatch").getString("state"))

        val source = reading.saveSource(null, "spectrometer calibration", "topic", "spectrometer calibration", 24, true).getString("id")
        repeat(64) { reading.saveSource(null, "其他来源$it", "topic", "topic $it", 24, true) }
        call("companion_reading", "action" to "progress", "id" to source, "text" to "旧进度：尚未检查误差")
        call("companion_reading", "action" to "progress", "id" to source, "text" to "spectrometer calibration 已核对误差，仍需验证温度影响")
        assertEquals(65, call("companion_reading", "action" to "sources").getInt("total"))
        assertEquals(5, call("companion_reading", "action" to "sources", "offset" to "60").getJSONArray("sources").length())
        val versions = call("companion_reading", "action" to "versions", "part" to "source", "id" to source).getJSONArray("history").objects()
        assertTrue(versions.any { it.getJSONObject("record").optString("progress") == "旧进度：尚未检查误差" })
        val frame = InnerStructureContext.prepare(context, role, chat, true, input = "spectrometer calibration 的误差验证到哪里了")
        assertTrue(frame.state, frame.state.contains("仍需验证温度影响"))
        assertTrue(frame.state.length < 100_000)
        val other = CharacterCardManager.getInstance(context).createCharacterCard(CharacterCard("", "另一助手"))
        withContext(AssistantScope.executionContext(other)) {
            try { reading.source(source); fail("must deny another owner") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun readingQueueAndNotesPreserveOldFilesAndIndependentAuthors() {
        val id = reading.add("最早线索", "https://example.com/first", "原始摘要").getString("id")
        repeat(205) { reading.add("线索$it", "https://example.com/$it", "原文$it") }
        assertEquals(200, reading.entries().size) // actual old queue behavior before activation
        enable()
        assertEquals(206, call("companion_reading", "action" to "queue").getInt("total"))
        call("companion_reading", "action" to "get", "id" to id)
        call("companion_reading", "action" to "note", "id" to id, "text" to "第一次理解")
        reading.note(id, "园园的批注", false)
        call("companion_reading", "action" to "note", "id" to id, "text" to "之后的新理解，原话保留")
        assertEquals("园园的批注", reading.entry(id).getString("user_note"))
        assertEquals("原始摘要", reading.entry(id).getString("summary"))
        val versions = reading.versions(id, "entry").getJSONArray("history").objects()
        assertTrue(versions.any { it.getJSONObject("record").optString("note") == "第一次理解" })
        assertTrue(versions.any { it.getJSONObject("record").optString("updated_by") == "user" })
        assertTrue(versions.any { it.getJSONObject("record").optString("updated_by") == role })
        val skipped = reading.entries().first().getString("id")
        reading.skip(skipped)
        assertTrue(reading.entryPage("retained").getJSONArray("items").objects().any { it.getString("id") == skipped })
        assertEquals("skipped", ReadingRoom(context, role).entry(skipped).getString("state"))
        assertFalse(store.read("reading-entry:$role:$id").has("delivered_at"))
    }

    @Test fun interruptedLegacyImportRepeatsWithoutDuplicatingCommittedHistory() {
        val id = task("迁移前事项"); board.comment(id, "user", "需要保留的原批注")
        reading.saveSource(null, "正常旧来源", "topic", "topic source", 24, true)
        // Truncated/invalid old source after a valid row simulates an interrupted upgrade input.
        store.update("reading:$role") { it.getJSONArray("sources").put(JSONObject().put("title", "缺少身份的损坏旧记录")) }
        enable()
        try { board.get(id); fail("malformed legacy input must not be silently adopted") } catch (_: org.json.JSONException) { }
        store.update("reading:$role") { it.getJSONArray("sources").remove(1) }
        assertEquals(2, notes(id).size)
        assertEquals(2, notes(id).size)
        assertEquals("需要保留的原批注", notes(id).first().getString("text"))
        assertTrue(context.filesDir.resolve("companion").listFiles()!!.any { it.name.endsWith(".subject-v0") })
    }

    @Test fun concurrentNotesAndRecoveryDoNotLoseProgressOrReclaimExecutedWork() {
        enable(); val id = task("并发记录")
        val pool = Executors.newFixedThreadPool(4)
        try {
            val writes = (0 until 4).map { writer -> pool.submit {
                repeat(25) { board.comment(id, role, "writer=$writer step=$it") }
            } }
            writes.forEach { it.get(60, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        val all = notes(id)
        assertEquals(101, all.size)
        assertEquals(100, all.filter { it.getString("kind") == "批注" }.map { it.getString("text") }.toSet().size)
        board.submit(id, role, role, setOf(role), 0)
        val event = board.get(id).getJSONObject("dispatch").getString("id")
        assertNotNull(board.claim(id, event)); TaskEvents.recover(TaskBoard(context, "role:$role", false))
        assertNull(board.claim(id, event)); assertNull(board.pending())
        assertTrue(notes(id).size > 101)
        store.finishTodo("role:$role", id, "delete")
        assertEquals(0, board.page().getInt("total"))
        assertEquals(0, TaskBoard(context, "role:$role", false).page().getInt("total"))
    }
}
