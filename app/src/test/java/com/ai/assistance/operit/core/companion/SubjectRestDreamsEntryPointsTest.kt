package com.ai.assistance.operit.core.companion

import android.app.Application
import android.content.Context
import android.net.Uri
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.api.chat.enhance.ConversationMarkupManager
import com.ai.assistance.operit.core.chat.CurrentRequestContext
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.core.tools.*
import com.ai.assistance.operit.data.model.*
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.data.backup.RawSnapshotBackupManager
import com.ai.assistance.operit.ui.permissions.PermissionLevel
import com.ai.assistance.operit.util.stream.StreamCollector
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Model content below is simulated. Production request composition, hidden report parsing,
 * adoption, native query, SQLite and full snapshot backup/restore are exercised directly. */
@RunWith(OperitStorageTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SubjectRestDreamsEntryPointsTest {
    private lateinit var context: Context
    private lateinit var role: String
    private lateinit var chat: String
    private lateinit var second: String
    private lateinit var handler: AIToolHandler
    @Before fun setup(): Unit = runBlocking {
        context = RuntimeEnvironment.getApplication()
        role = CharacterCardManager.getInstance(context).createCharacterCard(CharacterCard("", "梦测试", characterSetting = "初始参考"))
        InternalStateStore(context).configure(role, true)
        suspend fun window(): String {
            val c = ChatHistory(title = "私聊", messages = emptyList(), characterCardId = role)
            ChatHistoryManager.getInstance(context).saveChatHistory(c); return c.id
        }
        chat = window(); second = window()
        com.ai.assistance.operit.data.preferences.initAndroidPermissionPreferences(context)
        handler = AIToolHandler.getInstance(context)
        handler.registerDefaultTools(); registerAllTools(handler, context)
        handler.getToolPermissionSystem().saveMasterSwitch(PermissionLevel.ALLOW)
        handler.getToolPermissionSystem().saveToolPermission("companion_internal_state", PermissionLevel.ALLOW)
        CharacterCardManager.getInstance(context).setToolSelected(role, "builtin", "companion_internal_state", true)
    }
    private suspend fun request(window: String = chat, wake: Boolean = false) =
        InnerStructureContext.prepare(context, role, window, true, wake = wake, input = "延续此前的理解")
    private suspend fun submit(f: InternalStateFrame, fields: JSONObject): JSONObject {
        val report = JSONObject().put("frame_id", f.id).put("updates", JSONArray()).put("create", JSONArray())
        fields.keys().forEach { report.put(it, fields.get(it)) }
        val parser = InternalStateReportStream()
        assertEquals("正常表达", parser.append("<operit_state_report>$report</operit_state_report>正常表达") + parser.finish())
        return InnerStructureContext.submit(context, f, requireNotNull(parser.report))
    }
    private fun rest(state: String, version: Int) = JSONObject().put("expected_version", version)
        .put("state", state).put("meaning", "我选择暂歇或继续，未完成的事仍在").put("evidence", JSONArray())
    private fun dreaming() = JSONObject().put("rest", rest("resting", 0)).put("dream", JSONObject()
        .put("title", "海上的书房").put("content", "书页变成鲸鱼，窗外的月亮在倒着走。user在梦中没有认出我。")
        .put("sources", JSONArray()))
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("invalid candidate accepted") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
    }
    private fun item(kind: String, id: String) = InternalStateStore(context).inspectCategory(role, chat, kind, id, 0).getJSONObject("item")
    private suspend fun readMaterial(window: String, kind: String, id: String): InternalStateFrame {
        val s = request(window); val f = requireNotNull(s.internalStateFrame)
        val tool = AITool("companion_internal_state", listOf(ToolParameter("action", "get"), ToolParameter("section", kind), ToolParameter("id", id)))
        val results = ToolExecutionManager.executeInvocations(listOf(ToolInvocation(tool, "", 0..0)), context,
            handler, handler.getOrCreatePackageManager(), object : StreamCollector<String> { override suspend fun emit(value: String) {} },
            callerCardId = role, callerChatId = window, subjectFrame = f, subjectRound = 0).results
        assertTrue(results.single().error, results.single().success)
        val turns = CurrentRequestContext.stage(listOf(PromptTurn(PromptTurnKind.USER, "查阅自己此前的梦")), s) +
            PromptTurn(PromptTurnKind.TOOL_RESULT, ConversationMarkupManager.buildToolResultMessage(results), "tools")
        return requireNotNull(CurrentRequestContext.internalStateFrame(SubjectToolMaterials.attach(turns, System.currentTimeMillis(), results, context)))
    }
    private suspend fun createDream(): String {
        val f = requireNotNull(request().internalStateFrame)
        submit(f, dreaming())
        return "dream-" + InnerStructure.hash(f.id)
    }
    private fun interpretation(source: String, version: Int = 0) = JSONObject().put("id", "first-understanding")
        .put("expected_version", version).put("dream_source", source).put("meaning", "这是梦，不是她真的不认识我；我想把那一点失落记下来")
        .put("feeling", "醒后仍有一点失落").put("objects", JSONArray().put("user")).put("evidence", JSONArray().put(source))

    @Test fun normalPrivateDreamThenWakingUnderstandingSurvivesWindowsAndReopen(): Unit = runBlocking {
        InternalStateStore(context).materialBudget(role, chat, 16384)
        val id = createDream()
        val dream = item("dream", id)
        assertEquals("DREAM_NOT_REALITY", dream.getJSONObject("content").getString("reality"))
        assertEquals(0, dream.getJSONObject("content").getJSONObject("source_bindings").length())
        val frame = readMaterial(second, "dream", id)
        val source = frame.materialSources.entries.first { JSONObject(it.value).optString("type") == "dream_not_reality" }.key
        assertTrue(frame.materialSources.values.any { JSONObject(it).optJSONObject("locator")?.optString("kind") == "rest" })
        val fields = JSONObject().put("rest", rest("awake", 1)).put("dream_understanding", JSONArray().put(interpretation(source)))
        submit(frame, fields); submit(frame, fields)
        assertEquals(1, item("dream_understanding", "first-understanding").getInt("version"))
        val next = requireNotNull(request(chat, wake = true).internalStateFrame)
        assertTrue(next.materialSources.values.any { it.contains("醒后仍有一点失落") })
        // A simulated later interpretation can change the ordinary adopted feeling structure,
        // without a technical dream-success signal selecting any emotion on the model's behalf.
        val feeling = JSONObject().put("id", "after-dream").put("family", "feeling").put("kind", "梦后余波")
            .put("description", "梦不是真实行为，但我确实还在理解它").put("status", "active")
            .put("unresolved", true).put("current_state", "一点失落，不是对现实行为的指控")
        val proposal = JSONObject().put("op", "propose").put("id", "feeling-proposal").put("record", feeling)
            .put("expected_version", 0).put("origin", "subject_development").put("reason", "醒后自己的解释")
            .put("evidence", JSONArray().put(source))
        // Read the exact dream when a prior receipt competes for the shared material budget.
        val proposing = readMaterial(second, "dream", id)
        submit(proposing, JSONObject().put("inner", JSONArray().put(proposal)))
        val adopting = nativeInnerRead(context, requireNotNull(request().internalStateFrame), "inner_pending", "feeling-proposal")
        submit(adopting, JSONObject().put("inner", JSONArray().put(JSONObject().put("op", "adopt")
            .put("id", "feeling-proposal").put("reason", "明确采用这份感受解释"))))
        val continued = request(second)
        assertTrue(continued.state.contains("不是对现实行为的指控"))
        val group = com.ai.assistance.operit.data.preferences.CharacterGroupCardManager.getInstance(context)
            .createCharacterGroupCard(CharacterGroupCard("", "群", members = listOf(GroupMemberConfig(role))))
        val publicChat = ChatHistory(title = "群聊", messages = emptyList(), characterGroupId = group)
        ChatHistoryManager.getInstance(context).saveChatHistory(publicChat)
        val publicRequest = InnerStructureContext.prepare(context, role, publicChat.id, true, input = "群聊继续")
        assertFalse(publicRequest.state.contains("不是对现实行为的指控"))
        val publicRead = InternalStateStore(context).inspectCategory(role, publicChat.id, "inner", "after-dream", 0)
        assertFalse(publicRead.toString().contains("梦不是真实行为"))
        assertTrue(publicRead.getJSONObject("item").getBoolean("private_material_omitted"))
        assertEquals("awake", item("rest", "current").getJSONObject("content").getString("state"))
        // New store/request instances reopen real persisted heads; no in-memory dream cache.
        assertEquals(id, InternalStateStore(context).inspectCategory(role, second, "dream", id, 0).getJSONObject("item").getString("id"))
        val messages = ChatHistoryManager.getInstance(context).loadChatMessages(chat)
        assertTrue(messages.isEmpty()) // Internal dream generation is not a public chat send.
    }

    @Test fun dreamWithoutRealityEvidenceIsValidButWithoutRestIsAtomicRejection(): Unit = runBlocking {
        val f = requireNotNull(request().internalStateFrame)
        rejected { submit(f, JSONObject().put("dream", dreaming().getJSONObject("dream"))) }
        assertEquals(0, InternalStateStore(context).inspectCategory(role, chat, "dream", null, 0).getJSONArray("items").length())
        submit(f, dreaming())
        assertEquals(1, item("rest", "current").getInt("version"))
    }

    @Test fun wakingReviewArchivesDreamButKeepsItsAdoptedAftereffectAndRelevantIndex(): Unit = runBlocking {
        val states = InternalStateStore(context)
        states.materialBudget(role, chat, 16384)
        val id = createDream()
        var f = readMaterial(second, "dream", id)
        var source = f.materialSources.entries.first { JSONObject(it.value).optString("type") == "dream_not_reality" }.key
        submit(f, JSONObject().put("rest", rest("awake", 1)).put("dream_understanding", JSONArray().put(
            interpretation(source).put("retention", "active").put("retain_until", System.currentTimeMillis() + 172800000L)
                .put("keywords", JSONArray().put("月亮鲸鱼")))))
        f = readMaterial(chat, "dream", id)
        source = f.materialSources.entries.first { JSONObject(it.value).optString("type") == "dream_not_reality" }.key
        fun episode(name: String, category: String) = JSONObject().put("op", "appraise").put("id", name)
            .put("expected_version", 0).put("category_ref", category).put("objects", JSONArray().put("self"))
            .put("goals", JSONArray()).put("intensity", 0.8).put("importance", "梦后的真实感受")
            .put("long_term_impact", "可能影响今天的探索").put("attention", "foreground").put("status", "unresolved")
            .put("understanding", "知道那是梦，仍想弄清这份余韵").put("basis", "recollection")
            .put("reason", "受控模拟主体解释，程序不替主体选择情绪").put("evidence", JSONArray().put(source))
        submit(f, JSONObject().put("episodes", JSONArray().put(episode("dream-mood", "foundation:emotion:joy"))
            .put(episode("dream-curiosity", "foundation:drive:exploration"))))
        f = readMaterial(chat, "dream", id)
        f = nativeInnerRead(context, f, "episode", "dream-mood")
        f = nativeInnerRead(context, f, "episode", "dream-curiosity")
        source = f.materialSources.entries.first { JSONObject(it.value).optString("type") == "dream_not_reality" }.key
        val union = JSONObject().put("id", "dream-continuation").put("expected_version", 0).put("status", "active")
            .put("members", JSONArray(listOf("dream-mood", "dream-curiosity").map {
                JSONObject().put("kind", "episode").put("id", it).put("version", 1)
            })).put("objects", JSONArray()).put("evidence", JSONArray().put(source))
            .put("interpretation", "这份好奇牵住了梦后的愉快，暂时保持，不把梦当现实")
            .put("decision", "以后相关探索可再考虑这个感受")
            .put("effects", JSONArray().put(JSONObject().put("from", "episode:dream-curiosity").put("to", "episode:dream-mood")
                .put("meaning", "主体选择持续保持，后续可以改变").put("time_rule", JSONObject().put("curve", "hold"))))
        submit(f, JSONObject().put("unions", JSONArray().put(union)))
        val saved = states.read(role)
        val mood = CategoryEvents.row(saved, "episode", "dream-mood")!!
        val anchorAt = CategoryEvents.row(saved, "union", "dream-continuation")!!.getLong("at")
        val actualAnchor = CategoryEvents.timeView(saved, mood, anchorAt).getJSONObject("time_projection").getDouble("intensity")
        assertTrue(actualAnchor > 0)
        assertEquals(actualAnchor, CategoryEvents.timeView(saved, mood, anchorAt + 259200000L)
            .getJSONObject("time_projection").getDouble("intensity"), 0.00001)
        f = readMaterial(second, "dream", id)
        f = nativeInnerRead(context, f, "dream_understanding", "first-understanding")
        source = f.materialSources.entries.first { JSONObject(it.value).optString("type") == "dream_not_reality" }.key
        submit(f, JSONObject().put("dream_understanding", JSONArray().put(interpretation(source, 1).put("retention", "archive"))))
        assertTrue(DreamShelf.archived(item("dream", id), System.currentTimeMillis()))
        val ordinary = SubjectRestDreams.materials(states.read(role), "unrelatedzzzzzz", second)
        assertFalse(ordinary.any { it.optString("type") == "dream_index" && it.getJSONObject("locator").getString("id") == id })
        val remembered = InnerStructureContext.prepare(context, role, second, true, input = "想起月亮鲸鱼的梦")
        assertTrue(remembered.state.contains("dream_index"))
        assertTrue(remembered.state.contains("subject_adopted_time_rule"))
        assertTrue(requireNotNull(remembered.internalStateFrame).materialSources.values.map(::JSONObject)
            .filter { it.optString("type") == "dream_index" }.all { !it.toString().contains("书页变成鲸鱼") })
        assertEquals(1, item("episode", "dream-mood").getInt("version"))
        assertTrue(item("dream", id).getJSONObject("content").getString("content").contains("书页变成鲸鱼"))
    }

    @Test fun duplicatedReportDoesNotGenerateSecondDreamAndStaleReportCannotOverwrite(): Unit = runBlocking {
        val f = requireNotNull(request().internalStateFrame)
        val fields = dreaming(); submit(f, fields); submit(f, fields)
        assertEquals(1, InternalStateStore(context).inspectCategory(role, chat, "dream", null, 0).getJSONArray("items").length())
        rejected { submit(f, JSONObject().put("rest", rest("awake", 0))) }
        assertEquals("resting", item("rest", "current").getJSONObject("content").getString("state"))
    }

    @Test fun rereadingDreamDoesNotAdoptFeelingOrWakeSubject(): Unit = runBlocking {
        val id = createDream()
        repeat(3) { readMaterial(second, "dream", id) }
        assertEquals(1, item("dream", id).getInt("version"))
        assertEquals(1, item("rest", "current").getInt("version"))
        assertEquals(0, InternalStateStore(context).inspectCategory(role, chat, "dream_understanding", null, 0).getJSONArray("items").length())
    }

    @Test fun fabricatedDreamSourceRejectsWholeWakingCandidate(): Unit = runBlocking {
        createDream()
        val f = readMaterial(chat, "rest", "current")
        rejected { submit(f, JSONObject().put("rest", rest("awake", 1))
            .put("dream_understanding", JSONArray().put(interpretation("material:dream:invented:1")))) }
        assertEquals("resting", item("rest", "current").getJSONObject("content").getString("state"))
    }

    @Test fun anotherAssistantCannotReadDreamAndOwnRequestHasNoDream(): Unit = runBlocking {
        val id = createDream()
        val other = CharacterCardManager.getInstance(context).createCharacterCard(CharacterCard("", "另一个"))
        InternalStateStore(context).configure(other, true)
        val c = ChatHistory(title = "另一私聊", messages = emptyList(), characterCardId = other)
        ChatHistoryManager.getInstance(context).saveChatHistory(c)
        rejected { InternalStateStore(context).inspectCategory(role, c.id, "dream", id, 0) }
        val f = requireNotNull(InnerStructureContext.prepare(context, other, c.id, true, input = "你好").internalStateFrame)
        assertFalse(f.materialSources.values.any { it.contains("海上的书房") })
    }

    @Test fun groupCannotReadDreamOrSubmitPrivateRest(): Unit = runBlocking {
        val id = createDream()
        val group = com.ai.assistance.operit.data.preferences.CharacterGroupCardManager.getInstance(context)
            .createCharacterGroupCard(CharacterGroupCard("", "群", members = listOf(GroupMemberConfig(role))))
        val c = ChatHistory(title = "群聊", messages = emptyList(), characterGroupId = group)
        ChatHistoryManager.getInstance(context).saveChatHistory(c)
        rejected { InternalStateStore(context).inspectCategory(role, c.id, "dream", id, 0) }
        rejected { InternalStateStore(context).inspectCategory(role, c.id, "category_versions", "dream:$id", 0) }
        val s = InnerStructureContext.prepare(context, role, c.id, true, input = "群聊输入")
        assertFalse(s.state.contains("海上的书房"))
        rejected { submit(requireNotNull(s.internalStateFrame), JSONObject().put("rest", rest("awake", 1))) }
        assertEquals("resting", item("rest", "current").getJSONObject("content").getString("state"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @SQLiteMode(SQLiteMode.Mode.NATIVE)
    // A separate SDK sandbox gives this whole-app restore test fresh application classes,
    // including DataStore delegates and managers which retain Contexts across Robolectric
    // methods. Resetting Room alone allows an old manager to reopen another test's directory.
    @Config(sdk = [29], application = Application::class)
    @Test fun realFullSnapshotBackupRestoresDreamAndSubsequentNormalRead(): Unit = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val id = createDream()
            val foundationId = "foundation:drive:rest"
            suspend fun appraiseFoundation(value: Double) {
                val frame = readMaterial(second, "foundation", foundationId)
                submit(frame, JSONObject().put("foundation", JSONArray().put(JSONObject()
                    .put("id", foundationId).put("expected_version", frame.foundationVersions.getValue(foundationId))
                    .put("value", value).put("reason", "受控备份前后的明确重评")
                    .put("evidence", JSONArray().put(frame.eventIds.first())))))
            }
            appraiseFoundation(0.6)
            val original = item("dream", id).toString()
            SubjectLife(context).defer(role, chat, "restore-study", "study", "休息期间的一条批注")
            val input = SubjectMessageInbox(context).accept(role, chat, "restore-input", "重启后仍要保留的消息", emptyList())
            AssistantRoom(context, role).summaryAddress("园园")
            val arrange = readMaterial(second, "rest", "current")
            submit(arrange, JSONObject().put("rest", rest("resting", 1)
                .put("dream_plan", JSONObject().put("steps", 1))))
            val process = SubjectLife(context).next(role, chat, System.currentTimeMillis())!!
            ParticipatoryDreams(context).run(process, ParticipatoryDreams.Model { _, raw, _ -> JSONObject().put("actor", if (JSONObject(raw).has("subject_material")) "subject" else "environment").put("outcome", "continue").put("text", "梦中的纸船停在门边。").toString() })
            val processBefore = CompanionStore(context).activityRead(role, "dream_process", process.id)!!.toString()
            assertNotNull("source window must exist before export", ChatHistoryManager.getInstance(context).getChatMetadata(chat))
            val databasePath = context.getDatabasePath("app_database")
            val actualPath = requireNotNull(com.ai.assistance.operit.data.db.AppDatabase.getDatabase(context).openHelper.writableDatabase.path)
            assertEquals("Room must belong to this test application's storage", databasePath.canonicalPath, java.io.File(actualPath).canonicalPath)
            assertTrue("actual Room file must be present: $actualPath", databasePath.isFile)
            assertEquals("snapshot must include the actual Room directory", java.io.File(context.dataDir, "databases").canonicalFile, databasePath.parentFile!!.canonicalFile)
            // Normal dream preparation can advance valid time rules. Compare restore
            // against the exact export point, not an earlier pre-dream observation.
            val foundationBefore = InternalStateStore(context).read(role).getJSONObject("foundation_state").toString()
            val researchBoard = com.ai.assistance.operit.core.tasks.TaskBoard(context, "role:$role", false)
            val researchId = researchBoard.save(role, null, JSONObject().put("title", "备份中的研究进度"), setOf(role)).getString("id")
            repeat(90) { researchBoard.comment(researchId, role, "备份进度$it") }
            val readingRoom = ReadingRoom(context, role)
            val sourceId = readingRoom.saveSource(null, "备份中的方向", "topic", "research backup", 24, true).getString("id")
            readingRoom.progress(sourceId, "备份前第一版"); readingRoom.progress(sourceId, "备份前第二版")
            val researchBefore = researchBoard.get(researchId).toString()
            val sourceBefore = readingRoom.versions(sourceId, "source").toString()
            val zip = RawSnapshotBackupManager.exportToBackupDir(context)
            assertTrue(zip.length() > 0)
            val window = InternalStateStore(context).openEstablishedWindow(role)
            // Check exported facts, not only the ZIP size. No writes to either database.
            java.util.zip.ZipFile(zip).use { archive ->
                val entry = requireNotNull(archive.getEntry("payload/databases/app_database")) {
                    "Room file missing from export: path=$actualPath; entries=" + archive.entries().asSequence().map { it.name }.toList()
                }
                val copy = java.io.File(context.cacheDir, "verify-exported-chat.sqlite")
                archive.getInputStream(entry).use { input -> copy.outputStream().use { input.copyTo(it) } }
                android.database.sqlite.SQLiteDatabase.openDatabase(copy.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("SELECT id FROM chats WHERE id = ?", arrayOf(chat)).use { rows ->
                        assertTrue("exported database must contain the source window", rows.moveToFirst())
                    }
                }
                copy.delete()
            }
            val f = readMaterial(second, "rest", "current")
            submit(f, JSONObject().put("rest", rest("awake", 2)))
            appraiseFoundation(0.2)
            researchBoard.comment(researchId, role, "备份后新内容")
            readingRoom.progress(sourceId, "备份后第三版")
            RawSnapshotBackupManager.restoreFromBackupUri(context, Uri.fromFile(zip))
            // The existing restore UI requires an application restart. Simulate only that
            // singleton lifetime boundary; do not edit any persisted subject or chat data.
            ChatHistoryManager::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
            assertNotNull("restored Room database must contain the source window", ChatHistoryManager.getInstance(context).getChatMetadata(chat))
            // This backup predates the user's one-time opening. The local opening receipt
            // must keep its exact deadline instead of silently creating another 48 hours.
            val restoredWindow = InternalStateStore(context).openEstablishedWindow(role)
            assertEquals(window.getLong("opened_at"), restoredWindow.getLong("opened_at"))
            assertEquals(window.getLong("closes_at"), restoredWindow.getLong("closes_at"))
            assertEquals(foundationBefore, InternalStateStore(context).read(role).getJSONObject("foundation_state").toString())
            assertEquals(researchBefore, com.ai.assistance.operit.core.tasks.TaskBoard(context, "role:$role", false).get(researchId).toString())
            assertEquals(sourceBefore, ReadingRoom(context, role).versions(sourceId, "source").toString())
            assertEquals(91L, researchBoard.get(researchId).getLong("history_total"))
            val foundationHistory = InternalStateStore(context).inspectCategory(role, second, "foundation_history", foundationId, 0)
            assertEquals(1, foundationHistory.getJSONArray("items").length())
            assertEquals(original, item("dream", id).toString())
            assertEquals("resting", item("rest", "current").getJSONObject("content").getString("state"))
            assertEquals("休息期间的一条批注", CompanionStore(context).activityRead(role, "life_event", "restore-study")!!.getString("text"))
            assertEquals(input.getLong("timestamp"), SubjectMessageInbox(context).read(role, "restore-input").getLong("timestamp"))
            assertEquals("园园", AssistantRoom(context, role).read().getString("summary_user_address"))
            assertEquals(processBefore, CompanionStore(context).activityRead(role, "dream_process", process.id)!!.toString())
            assertEquals("梦中的纸船停在门边。", CompanionStore(context).activityRead(role, "dream_step", "${process.id}:1")!!.getString("text"))
            // The extra foundation appraisals are real competing material in the same 2048
            // token budget. Recovery must not force every saved item into the first request.
            // Verify the normal next-turn tool/continuation path really delivers this exact
            // restored dream, not merely that an archive inspection can find its database row.
            val resumed = readMaterial(second, "dream", id)
            assertTrue(resumed.materialSources.values.any {
                val source = JSONObject(it)
                source.optString("type") == "dream_not_reality" &&
                    source.optJSONObject("locator")?.optString("id") == id && it.contains("海上的书房")
            })
        } finally { Dispatchers.resetMain() }
    }

    @Test fun manyDreamsStayPagedAndHotSnapshotContainsOnlyLatestCommit(): Unit = runBlocking {
        val first = createDream()
        repeat(10) {
            val f = requireNotNull(request().internalStateFrame)
            submit(f, JSONObject().put("dream", JSONObject().put("title", "第${it}段梦")
                .put("content", "想象的风景$it").put("sources", JSONArray())))
        }
        val store = InternalStateStore(context)
        val one = store.inspectCategory(role, chat, "dream", null, 0)
        val two = store.inspectCategory(role, second, "dream", null, one.getInt("next_offset"))
        assertEquals(8, one.getJSONArray("items").length())
        assertEquals(3, two.getJSONArray("items").length())
        assertEquals(first, item("dream", first).getString("id"))
        assertEquals(1, store.read(role).getJSONObject("category_commit").getJSONArray("rows").length())
        val f = requireNotNull(request().internalStateFrame)
        assertTrue(f.materialSources.size <= 24)
        assertTrue(SubjectRestDreams.materials(store.read(role)).size <= 24)
    }
}
