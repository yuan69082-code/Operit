package com.ai.assistance.operit.core.companion

import android.app.Application
import android.content.Context
import com.ai.assistance.operit.data.model.CharacterCard
import com.ai.assistance.operit.data.model.ChatHistory
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Simulated model responses; actual report parsing, request preparation and archive are used. */
@RunWith(OperitStorageTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SubjectLifeEntryPointsTest {
    private lateinit var context: Context
    private lateinit var role: String
    private lateinit var chat: String
    @Before fun setup() = runBlocking {
        context = RuntimeEnvironment.getApplication()
        // Robolectric creates a new Application per method. Do not let a singleton
        // inspect the previous Application's enabled-state preferences during migration.
        CharacterCardManager::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
        ChatHistoryManager::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
        role = CharacterCardManager.getInstance(context).createCharacterCard(CharacterCard("", "生活测试", characterSetting = "初始参考"))
        InternalStateStore(context).configure(role, true)
        val c = ChatHistory(title = "私聊", messages = emptyList(), characterCardId = role)
        ChatHistoryManager.getInstance(context).saveChatHistory(c)
        chat = c.id
        AssistantWindowRouter.selected(context, c)
    }
    @Test fun existingInternalCapabilityGainsLifeControlOnceAndRespectsLaterDeselection() = runBlocking {
        val cards = CharacterCardManager.getInstance(context)
        val other = cards.createCharacterCard(CharacterCard("", "已有助手"))
        val original = cards.getCharacterCard(other)
        cards.updateCharacterCard(original.copy(toolAccessConfig = original.toolAccessConfig.copy(
            enabled = true, allowedBuiltinTools = listOf("companion_internal_state", "sleep"))))
        InternalStateStore(context).configure(other, true)
        cards.ensureIndependentSettings(other)
        assertTrue(cards.getCharacterCard(other).toolAccessConfig.toString(),
            "companion_life" in cards.getCharacterCard(other).toolAccessConfig.allowedBuiltinTools)
        assertTrue("sleep" in cards.getCharacterCard(other).toolAccessConfig.allowedBuiltinTools)
        cards.setToolSelected(other, "builtin", "companion_life", false)
        cards.ensureIndependentSettings(other)
        assertFalse("companion_life" in cards.getCharacterCard(other).toolAccessConfig.allowedBuiltinTools)
    }

    private suspend fun adopt(fields: JSONObject) {
        val request = InnerStructureContext.prepare(context, role, chat, true, input = "当前生活选择")
        val f = requireNotNull(request.internalStateFrame)
        commit(f, fields)
    }
    private suspend fun commit(f: InternalStateFrame, fields: JSONObject) {
        val report = JSONObject().put("frame_id", f.id).put("updates", JSONArray()).put("create", JSONArray())
        fields.keys().forEach { report.put(it, fields.get(it)) }
        val parser = InternalStateReportStream()
        parser.append("<operit_state_report>$report</operit_state_report>正文")
        parser.finish()
        InnerStructureContext.submit(context, f, requireNotNull(parser.report))
    }
    private fun rest(plan: JSONObject? = null): JSONObject = JSONObject().put("rest", JSONObject()
        .put("expected_version", 0).put("state", "resting").put("mode", "sleep")
        .put("until", System.currentTimeMillis() + 3_600_000).put("meaning", "暂停一会儿，之后再接上")
        .put("evidence", JSONArray()).apply { if (plan != null) put("dream_plan", plan) })

    @Test fun pausedAutomationDoesNotTrapOrdinaryMessagesOrRewriteRest() = runBlocking {
        adopt(rest())
        val life = SubjectLife(context)
        val preferences = CompanionStore(context).preferences
        preferences.edit().putBoolean("autonomous_enabled", false).commit()
        assertFalse(life.defer(role, chat, "input:paused", "user_message", "新消息"))
        assertEquals("resting", life.rest(role)!!.getJSONObject("content").getString("state"))
        preferences.edit().putBoolean("autonomous_enabled", true).commit()
        assertTrue(life.defer(role, chat, "input:enabled", "user_message", "遵循原休息安排"))
    }

    @Test fun sleepBuffersEventsAndOnlyDispatchesTheChosenEndOnce() = runBlocking {
        adopt(rest())
        val life = SubjectLife(context)
        repeat(12) { assertTrue(life.defer(role, chat, "book-$it", "study", "第${it}条批注")) }
        assertTrue(life.defer(role, chat, "book-0", "study", "第0条批注"))
        assertEquals(12L, CompanionStore(context).activityCount(role, "life_event", "pending"))
        assertNull(life.next(role, chat, System.currentTimeMillis()))
        val due = life.rest(role)!!.getJSONObject("content").getLong("until")
        assertNotNull(life.next(role, chat, due))
        assertNull(SubjectLife(context).next(role, chat, due + 1))
        assertEquals(12L, CompanionStore(context).activityCount(role, "life_event", "pending"))
        assertEquals("resting", life.rest(role)!!.getJSONObject("content").getString("state"))
    }
    @Test fun wakeChoiceHasCurrentReadProofAndPersistsAfterToolContinuation() = runBlocking {
        val choice = rest(JSONObject().put("steps", 3))
        choice.getJSONObject("rest").put("meaning", "保留完整休息理由".repeat(2000))
        adopt(choice)
        val life = SubjectLife(context)
        val end = life.rest(role)!!.getJSONObject("content").getLong("until")
        val wake = requireNotNull(life.next(role, chat, end))
        assertFalse(wake.text.contains("source_bindings"))
        assertTrue(wake.text.length < 700)
        val request = InnerStructureContext.prepare(context, role, chat, true, input = wake.text)
        val f = requireNotNull(request.internalStateFrame)
        val control = JSONObject(requireNotNull(f.materialSources["material:rest:current:1"]))
        assertEquals(1, control.getJSONObject("locator").getInt("version"))
        assertTrue(control.getBoolean("control_state"))
        assertTrue(control.getString("text").length < 800)
        assertTrue(life.rest(role)!!.getJSONObject("content").getString("meaning").length > 10_000)
        val fields = JSONObject().put("rest", JSONObject().put("expected_version", 1).put("state", "awake")
            .put("meaning", "梦结束后，我决定醒来并告诉园园").put("evidence", JSONArray().put(f.eventIds.first())))
        // Reproduce the reported rejection: a notice/version inferred from a dream is not
        // itself a current rest read. The production composer must supply that read.
        try {
            commit(f.copy(materialSources = f.materialSources.filterKeys { !it.startsWith("material:rest:") }), fields)
            fail("Unread rest version must not be silently adopted")
        } catch (failure: InternalStateReportIssue) {
            assertEquals("ISR_UNREAD_VERSION", failure.code)
            assertEquals("rest.expected_version", failure.path)
        }
        val fresh = InnerStructureContext.prepare(context, role, chat, true, input = wake.text)
        var turns = com.ai.assistance.operit.core.chat.CurrentRequestContext.stage(
            listOf(com.ai.assistance.operit.core.chat.hooks.PromptTurn(com.ai.assistance.operit.core.chat.hooks.PromptTurnKind.USER, wake.text)), fresh)
        repeat(2) {
            turns = SubjectToolMaterials.attach(turns + com.ai.assistance.operit.core.chat.hooks.PromptTurn(
                com.ai.assistance.operit.core.chat.hooks.PromptTurnKind.TOOL_RESULT, "已读取梦中步骤$it", "companion_internal_state"), end + it)
        }
        val afterTools = requireNotNull(com.ai.assistance.operit.core.chat.CurrentRequestContext.internalStateFrame(turns))
        fields.getJSONObject("rest").put("evidence", JSONArray().put(afterTools.eventIds.first()))
        commit(afterTools, fields)
        life.finish(wake, "completed")
        val reopened = SubjectLife(context).rest(role)!!
        assertEquals(2, reopened.getInt("version"))
        assertEquals("awake", reopened.getJSONObject("content").getString("state"))
        assertEquals("空闲", SubjectRestPolicy.label(reopened.getJSONObject("content"), null, end + 1))
        assertNull(SubjectLife(context).next(role, chat, end + 1))
    }
    @Test fun anExistingEngineWindowDoesNotStartASecondLocalLifeRunner() = runBlocking {
        adopt(rest(JSONObject().put("steps", 2)))
        val life = SubjectLife(context)
        val end = life.rest(role)!!.getJSONObject("content").getLong("until")
        com.ai.assistance.operit.core.engine.EngineWindow(context).bind(role, "existing-subject", false, emptySet())
        assertNull(life.next(role, chat, end + 1))
        assertFalse(life.defer(role, chat, "external-input", "user_message", "原入口继续处理"))
        assertEquals(0L, CompanionStore(context).activityCount(role, "life_run", "technical"))
        // Preserve the dormant arrangement; do not rewrite the existing subject state.
        assertEquals("sleep", CategoryEvents.row(InternalStateStore(context).read(role), "rest", "current")!!
            .getJSONObject("content").getString("mode"))
    }
    @Test fun acceptedMessagesSurviveReopenAndClaimIsIdempotent() {
        val inbox = SubjectMessageInbox(context)
        val accepted = inbox.accept(role, chat, "input-one", "自己的原始消息", emptyList())
        assertEquals(accepted.getLong("timestamp"), SubjectMessageInbox(context).accept(role, chat, "input-one", "自己的原始消息", emptyList()).getLong("timestamp"))
        assertTrue(inbox.claim(role, "input-one"))
        assertFalse(inbox.claim(role, "input-one"))
        SubjectLife(context).recover(role)
        assertEquals("unknown", inbox.read(role, "input-one").getString("status"))
        assertFalse(inbox.claim(role, "input-one"))
    }
    @Test fun concurrentDeliveryOfOneInputKeepsOneReceiptAndOneClaim() = runBlocking {
        val receipts = (0 until 12).map {
            async(Dispatchers.IO) { SubjectMessageInbox(context).accept(role, chat, "concurrent-input", "同一条输入", emptyList()) }
        }.awaitAll()
        assertEquals(1, receipts.map { it.getLong("timestamp") }.distinct().size)
        val claims = (0 until 12).map {
            async(Dispatchers.IO) { SubjectMessageInbox(context).claim(role, "concurrent-input") }
        }.awaitAll()
        assertEquals(1, claims.count { it })
        assertEquals(1L, CompanionStore(context).activityCount(role, "message_inbox", "pending"))
        SubjectLife(context).recover(role)
        assertEquals("unknown", SubjectMessageInbox(context).read(role, "concurrent-input").getString("status"))
        assertNull(SubjectMessageInbox(context).next(role))
    }
    private fun dreamResponse(raw: String, text: String, outcome: String = "continue") = JSONObject()
        .put("actor", if (JSONObject(raw).has("subject_material")) "subject" else "environment")
        .put("outcome", outcome).put("text", text).toString()

    private fun fullLength(plan: JSONObject): kotlin.random.Random = kotlin.random.Random((0..1000).first {
        DreamDesign.draw(plan, kotlin.random.Random(it)).getInt("interactions") == plan.getInt("steps")
    })

    @Test fun dreamAlternatesEnvironmentAndSubjectWithoutRealToolsAndReachesLaterRequest() = runBlocking {
        adopt(rest(JSONObject().put("steps", 2).put("tone", "复杂恐惧").put("form", "跳跃")))
        val work = requireNotNull(SubjectLife(context).next(role, chat, System.currentTimeMillis()))
        assertTrue(work.dream)
        val requests = mutableListOf<Pair<String, JSONObject>>()
        ParticipatoryDreams(context).run(work, ParticipatoryDreams.Model { system, raw, partial ->
            val input = JSONObject(raw)
            assertTrue("Dream input must share the default material budget", SubjectMaterialBudget.estimate(raw) <= 2048)
            requests += system to input
            val text = if (input.has("subject_material")) "我先走近看看，同时仍有些犹豫。" else "眼前是一道没见过的门，里面传来不确定的声音。"
            val response = dreamResponse(raw, text)
            partial(response)
            response
        }, random = fullLength(JSONObject(work.text)))
        assertEquals(4, requests.size)
        assertEquals("眼前是一道没见过的门，里面传来不确定的声音。", requests[2].second.getString("previous_own_fragment"))
        assertEquals("我先走近看看，同时仍有些犹豫。", requests[2].second.getString("previous_fragment"))
        assertEquals("我先走近看看，同时仍有些犹豫。", requests[3].second.getString("previous_own_fragment"))
        assertFalse(requests[0].second.has("subject_material"))
        assertTrue(requests[1].second.has("subject_material"))
        assertEquals("我先走近看看，同时仍有些犹豫。", requests[2].second.getString("previous_fragment"))
        val stored = CompanionStore(context).activityRead(role, "dream_process", work.id)!!
        assertEquals("completed", stored.getString("status"))
        assertEquals("DREAM_NOT_REALITY", stored.getString("reality"))
        InternalStateStore(context).materialBudget(role, chat, 16384)
        val later = InnerStructureContext.prepare(context, role, chat, true, input = "醒来后想接上之前的事")
        assertTrue(later.state.contains("dream_index"))
        assertEquals(0, InternalStateStore(context).inspectCategory(role, chat, "action", null, 0).getJSONArray("items").length())
    }
    @Test fun refusalIsNotCompletedAndExplicitRetryRetainsTheDrawAndIngredients() = runBlocking {
        adopt(rest(JSONObject().put("steps", 2).put("theme_mode", "multiple").put("theme_count", 2)))
        val life = SubjectLife(context)
        val work = requireNotNull(life.next(role, chat, System.currentTimeMillis()))
        var scenes = 0
        var participants = 0
        try {
            ParticipatoryDreams(context).run(work,
                model = ParticipatoryDreams.Model { _, raw, _ -> participants++; dreamResponse(raw, "该模型无法生成本步。", "unavailable") },
                environmentModel = ParticipatoryDreams.Model { system, raw, _ ->
                    scenes++; assertTrue(system.contains("固定职能")); assertFalse(system.contains("你是生活测试"))
                    dreamResponse(raw, "门外是正在落雪的街道。")
                }, random = fullLength(JSONObject(work.text)))
            fail("a refusal must not complete the dream")
        } catch (_: IllegalStateException) { }
        val store = CompanionStore(context)
        val stopped = store.activityRead(role, "dream_process", work.id)!!
        val draw = stopped.getJSONObject("design").toString()
        val ingredients = stopped.getJSONObject("ingredients").toString()
        assertEquals("refused", stopped.getString("status"))
        assertEquals(1, stopped.getInt("next_step"))
        assertEquals("refused", store.activityRead(role, "dream_step", "${work.id}:1")!!.getString("status"))
        assertEquals(1, scenes); assertEquals(1, participants)
        assertFalse(ParticipatoryDreams.materials(context, role).single().getString("text").contains("该模型无法生成"))
        assertNull(life.next(role, chat, System.currentTimeMillis()))
        life.recover(role)
        life.dreamChoice(role, work.id, "retry_step", "核对实际中断，只重试未完成的一步")
        val resume = requireNotNull(life.next(role, chat, System.currentTimeMillis()))
        val nextScenes = mutableListOf<String>()
        ParticipatoryDreams(context).run(resume,
            model = ParticipatoryDreams.Model { _, raw, _ -> dreamResponse(raw, "我沿着灯光往前走。") },
            environmentModel = ParticipatoryDreams.Model { _, raw, _ -> nextScenes += raw; dreamResponse(raw, "路口出现了一间小屋。") },
            random = kotlin.random.Random(999))
        val ended = store.activityRead(role, "dream_process", work.id)!!
        assertEquals(draw, ended.getJSONObject("design").toString())
        assertEquals(ingredients, ended.getJSONObject("ingredients").toString())
        assertEquals("completed", ended.getString("status"))
        assertEquals(4, ended.getInt("next_step"))
        assertEquals(1, nextScenes.size)
        val index = InternalStateStore(context).inspectCategory(role, chat, "dream", null, 0)
        val brief = index.getJSONObject("participatory_processes").getJSONArray("items").getJSONObject(0)
        assertEquals(work.id, brief.getString("id"))
        assertFalse(brief.has("ingredients"))
        assertTrue(life.page(role, "dream_process", 0, work.id).getJSONObject("item").has("ingredients"))
    }

    @Test fun malformedCommentaryStaysDiagnosticAndNaturalSubjectEndingIsAllowed() = runBlocking {
        adopt(rest(JSONObject().put("steps", 3)))
        val life = SubjectLife(context)
        val work = life.next(role, chat, System.currentTimeMillis())!!
        try {
            ParticipatoryDreams(context).run(work, model = ParticipatoryDreams.Model { _, _, _ -> "An outside opinion, not a dream. <dream_end/>" })
            fail()
        } catch (_: DreamResponse.Invalid) { }
        val store = CompanionStore(context)
        assertEquals("invalid_response", store.activityRead(role, "dream_process", work.id)!!.getString("status"))
        assertEquals(0, store.activityRead(role, "dream_process", work.id)!!.getInt("next_step"))
        assertFalse(ParticipatoryDreams.materials(context, role).single().getString("text").contains("An outside opinion"))
        life.dreamChoice(role, work.id, "retry_step", "明确重试场景")
        val resume = life.next(role, chat, System.currentTimeMillis())!!
        ParticipatoryDreams(context).run(resume, model = ParticipatoryDreams.Model { _, raw, _ ->
            if (JSONObject(raw).has("subject_material")) dreamResponse(raw, "我决定结束这一场梦。", "end")
            else dreamResponse(raw, "窗前落着柔和的光。")
        })
        val finished = store.activityRead(role, "dream_process", work.id)!!
        assertEquals("completed", finished.getString("status"))
        assertEquals("subject_ended", finished.getString("end_reason"))
        assertEquals(2, finished.getInt("next_step"))
    }

    @Test fun sceneModelChoiceIsPerAssistantAndDoesNotRewriteTheSubjectModel() = runBlocking {
        val manager = com.ai.assistance.operit.data.preferences.ModelConfigManager(context)
        val id = manager.createConfig("梦境辅助测试")
        manager.saveModelConfig(requireNotNull(manager.getModelConfig(id)).copy(modelName = "scene-a,scene-b"))
        val before = CharacterCardManager.getInstance(context).getCharacterCard(role)
        val settings = DreamSettings(context)
        settings.save(role, DreamSettings.Binding(id, 1))
        assertEquals(DreamSettings.Binding(id, 1), DreamSettings(context).read(role))
        val other = CharacterCardManager.getInstance(context).createCharacterCard(CharacterCard("", "另一个主体"))
        assertNull(settings.read(other))
        val after = CharacterCardManager.getInstance(context).getCharacterCard(role)
        assertEquals(before.chatModelConfigId, after.chatModelConfigId)
        assertEquals(before.chatModelIndex, after.chatModelIndex)
        settings.save(role, null)
        assertNull(settings.read(role))
    }

    @Test fun failedDreamKeepsPartialAndDoesNotAutomaticallyRunAgain() = runBlocking {
        adopt(rest(JSONObject().put("steps", 1)))
        val work = requireNotNull(SubjectLife(context).next(role, chat, System.currentTimeMillis()))
        try {
            ParticipatoryDreams(context).run(work, ParticipatoryDreams.Model { _, _, save -> save("记得窗边的一道光。"); error("network unavailable") })
            fail()
        } catch (_: IllegalStateException) { }
        assertEquals("记得窗边的一道光。", CompanionStore(context).activityRead(role, "dream_step", "${work.id}:0")!!.getString("raw_response"))
        val retained = ParticipatoryDreams.materials(context, role).single().getString("text")
        assertFalse(retained.contains("记得窗边的一道光。"))
        assertTrue(retained.contains("failed"))
        assertNull(SubjectLife(context).next(role, chat, System.currentTimeMillis()))
    }

    @Test fun streamingCheckpointsDoNotBecomeDozensOfDreamHistoryCopies(): Unit = runBlocking {
        adopt(rest(JSONObject().put("steps", 1)))
        val work = SubjectLife(context).next(role, chat, System.currentTimeMillis())!!
        ParticipatoryDreams(context).run(work, ParticipatoryDreams.Model { _, raw, partial ->
            repeat(12) { partial("原始流式片段".repeat((it + 1) * 100)) }
            dreamResponse(raw, "这一段已经实际生成。")
        })
        for (i in 0..1) {
            val history = InternalStateStore(context).inspectCategory(role, chat, "category_versions", "dream_step:${work.id}:$i", 0)
                .getJSONArray("items")
            assertEquals(1, history.length())
            assertEquals("completed", history.getJSONObject(0).getString("status"))
        }
        val group = com.ai.assistance.operit.data.preferences.CharacterGroupCardManager.getInstance(context)
            .createCharacterGroupCard(com.ai.assistance.operit.data.model.CharacterGroupCard("", "群", members = listOf(com.ai.assistance.operit.data.model.GroupMemberConfig(role))))
        val groupChat = ChatHistory(title = "群", messages = emptyList(), characterGroupId = group)
        ChatHistoryManager.getInstance(context).saveChatHistory(groupChat)
        assertThrows(IllegalArgumentException::class.java) {
            InternalStateStore(context).inspectCategory(role, groupChat.id, "category_versions", "dream_step:${work.id}:0", 0)
        }
    }

    @Test fun chosenInterruptionPausesDreamAtStepBoundaryAndKeepsCompletedFragment() = runBlocking {
        adopt(rest(JSONObject().put("steps", 3)))
        val life = SubjectLife(context)
        val dream = life.next(role, chat, System.currentTimeMillis())!!
        var calls = 0
        try {
            ParticipatoryDreams(context).run(dream, ParticipatoryDreams.Model { _, raw, _ ->
                calls++
                life.requestWake(role, chat)
                dreamResponse(raw, "走廊尽头有一点灯光。")
            })
            fail("chosen interruption must pause before the next request")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(1, calls)
        assertEquals("completed", CompanionStore(context).activityRead(role, "dream_step", "${dream.id}:0")!!.getString("status"))
        assertEquals("interrupted", CompanionStore(context).activityRead(role, "dream_process", dream.id)!!.getString("status"))
        val interruption = life.next(role, chat, System.currentTimeMillis())!!
        assertFalse(interruption.dream)
        assertTrue(interruption.text.contains("user_wake_request"))
        assertNull(life.next(role, chat, System.currentTimeMillis()))
    }

    @Test fun missingConversationPreflightStopsAutomaticRetryWithoutLosingInput() = runBlocking {
        val inbox = SubjectMessageInbox(context)
        val original = inbox.accept(role, "deleted-chat", "stale", "不要丢掉这句话", emptyList())
        assertNull(ChatHistoryManager.getInstance(context).getChatMetadata("deleted-chat"))
        assertTrue(inbox.rejectQueued(role, "deleted-chat", "stale", "会话不存在"))
        val reopened = SubjectMessageInbox(context)
        assertNull(reopened.next(role))
        val saved = reopened.read(role, "stale")
        assertEquals("failed", saved.getString("status"))
        assertEquals("会话不存在", saved.getString("dispatch_error"))
        assertEquals(original.getString("text"), saved.getString("text"))
        assertEquals(original.getString("source_chat"), saved.getString("source_chat"))
        assertEquals(original.getLong("timestamp"), saved.getLong("timestamp"))
        assertFalse(reopened.rejectQueued(role, "deleted-chat", "stale", "会话不存在"))
        reopened.accept(role, chat, "valid", "有效会话的新消息", emptyList())
        assertEquals("valid", SubjectLife(context).next(role, chat, System.currentTimeMillis())!!.input!!.getString("id"))
    }

    @Test fun latePreflightRejectionCannotResetAClaimedInput() {
        val inbox = SubjectMessageInbox(context)
        inbox.accept(role, chat, "claimed", "原文", emptyList())
        assertTrue(inbox.claim(role, "claimed"))
        assertFalse(inbox.rejectQueued(role, chat, "claimed", "旧请求的失败"))
        assertEquals("sending", inbox.read(role, "claimed").getString("status"))
        assertFalse(inbox.read(role, "claimed").has("dispatch_error"))
    }

    @Test fun earlierFailedInputsDoNotStarveNewInputAndCompletedInputDoesNotWakeTwice() = runBlocking {
        adopt(rest())
        val inbox = SubjectMessageInbox(context)
        repeat(12) { i ->
            inbox.accept(role, chat, "old-$i", "旧输入$i", emptyList())
            inbox.finish(role, "old-$i", "unknown")
        }
        val input = inbox.accept(role, chat, "next", "新输入", emptyList())
        SubjectLife(context).defer(role, chat, "input:next", "user_message", "新输入")
        assertEquals("next", inbox.next(role)!!.getString("id"))
        assertTrue(inbox.claim(role, "next"))
        inbox.finish(role, "next", "completed")
        assertFalse(inbox.claim(role, "next"))
        assertEquals(input.getLong("timestamp"), inbox.accept(role, chat, "next", "新输入", emptyList()).getLong("timestamp"))
        assertEquals("handled", CompanionStore(context).activityRead(role, "life_event", "input:next")!!.getString("status"))
        assertNull(inbox.next(role))
    }

    @Test fun experienceAndDirectionAreReadLaterAndCanSupportRealActionDeclaration() = runBlocking {
        InternalStateStore(context).materialBudget(role, chat, 16384)
        val request = InnerStructureContext.prepare(context, role, chat, true, input = "我刚试的资料整理只完成了第一段，其他部分尚未完成。")
        val frame = request.internalStateFrame!!
        val source = frame.eventIds.last()
        fun content(kind: String, id: String, text: String) = JSONObject().put("kind", kind).put("id", id)
            .put("expected_version", 0).put("text", text).put("scope", "这次资料整理")
            .put("uncertainty", "下一次是否适用尚待观察").put("status", "considering").put("evidence", JSONArray().put(source))
        commit(frame, JSONObject().put("life", JSONArray()
            .put(content("experience", "organize", "实际只完成了一部分；先核对已有结果，不能当成全部成功。"))
            .put(content("direction", "project", "我仍想把资料整理清楚，也可能改变做法。")
                .put("purpose", "保留自己的发现").put("goal", "可重新查到出处")
                .put("resolve", "投入一段有限时间，再看结果").put("ambition", "逐渐形成自己的整理方法"))))
        val later = InnerStructureContext.prepare(context, role, chat, true, input = "想继续整理时之前有哪些实际结果？")
        val next = later.internalStateFrame!!
        val material = next.materialSources.values.map(::JSONObject).first { it.optString("type") == "subject_direction" }
        assertTrue(later.state.contains("保留自己的发现"))
        SubjectActions.validateDeclaration(JSONObject().put("intention_refs", JSONArray().put(JSONObject()
            .put("kind", "direction").put("id", "project").put("version", material.getJSONObject("locator").getInt("version"))))
            .put("evidence", JSONArray().put(material.getString("id"))), next)
        val stored = InternalStateStore(context).inspectCategory(role, chat, "experience", "organize", 0).getJSONObject("item")
        assertTrue(stored.getJSONObject("content").getBoolean("not_a_plan"))
        assertTrue(stored.getJSONObject("content").getJSONObject("bindings").has(source))
        assertFalse(CompanionStore(context).read("schedule:$chat").has("at"))
    }

    @Test fun restDeferralKeepsOriginalScheduleAndActivityBudgetAcrossResume() {
        val schedule = JSONObject().put("at", 100L).put("purpose", "自己的资料整理").put("owner", "ai")
        CompanionSchedule.claim(schedule, "ticket", 200)
        CompanionSchedule.defer(schedule, "ticket")
        assertEquals(100L, schedule.getLong("at"))
        assertEquals("deferred", CompanionSchedule.state(schedule))
        val ref = ActivityNotes.Ref(role, chat, "same-activity")
        val life = SubjectLife(context)
        life.checkpoint(ref, "原活动", "executing", exploration = true)
        life.checkpoint(ref, "原活动", "paused", toolsUsed = 7)
        life.chooseActivity(role, ref.id, "continue", "先回复，随后接着原处")
        val work = life.next(role, chat, System.currentTimeMillis())!!
        assertEquals(ref.id, work.activity)
        assertTrue(life.isExploration(role, work.activity))
        val budget = CompanionToolBudget()
        budget.reset(life.toolsUsed(role, work.activity))
        repeat(5) { assertTrue(budget.admit("read_file", null)) }
        assertFalse(budget.admit("write_file", null))
        assertNull(life.next(role, chat, System.currentTimeMillis()))
    }

    @Test fun explicitWakeIsDistinctFromOrdinaryMessagesAndDoesNotPretendAlreadyAwake() = runBlocking {
        adopt(rest())
        val life = SubjectLife(context)
        repeat(15) { life.defer(role, chat, "quiet-$it", "user_message", "普通输入$it") }
        assertNull(life.next(role, chat, System.currentTimeMillis()))
        val request = life.requestWake(role, chat)
        assertEquals(request.getString("id"), life.requestWake(role, chat).getString("id"))
        val work = life.next(role, chat, System.currentTimeMillis())!!
        assertTrue(work.text.contains("user_wake_request"))
        assertTrue(work.text.contains(request.getString("id")))
        assertNull(life.next(role, chat, System.currentTimeMillis()))
        assertEquals("resting", life.rest(role)!!.getJSONObject("content").getString("state"))
    }

    @Test fun pendingMaterialPagesRotateWithoutChangingAcknowledgement() = runBlocking {
        adopt(rest())
        repeat(17) { SubjectLife(context).defer(role, chat, "many-$it", "study", "不同材料$it") }
        val seen = mutableSetOf<String>()
        repeat(12) {
            InnerStructureContext.prepare(context, role, chat, true, input = "这次取用适当材料")
            seen += SubjectLife(context).materials(role).filter { it.getString("type") == "life_event" }
                .map { it.getJSONObject("locator").getString("id") }
        }
        assertEquals(17, seen.size)
        assertEquals(17L, CompanionStore(context).activityCount(role, "life_event", "pending"))
    }

    @Test fun resumedActivityKeepsDistinctRequestVersionsAndOriginalNotes() = runBlocking {
        val ref = ActivityNotes.begin(context, role, chat, "same-trigger", "exploration")
        try {
            val life = SubjectLife(context)
            life.checkpoint(ref, "想整理一个问题", "executing", exploration = true)
            val first = InnerStructureContext.prepare(context, role, chat, true, input = "开始").internalStateFrame!!
            life.recordRequest(ref, first, 0)
            ActivityNotes.note(context, ref, "${first.id}:0:note:0", "我先保留这个疑问。", false)
            life.checkpoint(ref, "想整理一个问题", "paused", toolsUsed = 2)
            life.chooseActivity(role, ref.id, "continue", "接上原来的疑问")
            val second = InnerStructureContext.prepare(context, role, chat, true, input = "继续").internalStateFrame!!
            life.recordRequest(ref, second, 0)
            ActivityNotes.note(context, ref, "${second.id}:0:note:0", "现在接着核对已经取得的结果。", false)
            val parts = ActivityNotes.page(context, role, "activity_part", ref.id, 0).getJSONArray("items")
            assertEquals(4, parts.length())
            assertNotEquals(first.id, second.id)
            assertTrue(parts.toString().contains("我先保留这个疑问。"))
            assertTrue(parts.toString().contains("现在接着核对已经取得的结果。"))
            assertEquals(2, life.toolsUsed(role, ref.id))
        } finally { ActivityNotes.release(ref) }
    }
    private fun privateLifeTool(action: String, details: JSONObject): JSONObject {
        val handler = com.ai.assistance.operit.core.tools.AIToolHandler.getInstance(context)
        RoomTools.register(handler, context)
        val args = mapOf("action" to action, "details" to details.toString(),
            "__operit_package_chat_id" to chat, "__operit_package_caller_card_id" to role)
        val result = handler.executeTool(com.ai.assistance.operit.data.model.AITool("companion_life",
            args.map { com.ai.assistance.operit.data.model.ToolParameter(it.key, it.value) }))
        assertTrue(result.error, result.success)
        return JSONObject(result.result.toString())
    }

    private fun privateConfig() = JSONObject().put("expected_version", 0).put("enabled", true)
        .put("start", "01:00").put("end", "07:00").put("zone", "Asia/Shanghai")
    private fun privateWindow() = PrivateTimePolicy.window(privateConfig(), java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(2))

    @Test fun privateTimePersistsPlanningAndProgressAcrossIndependentActivities() = runBlocking {
        val time = PrivateTime(context)
        val w = privateWindow()
        assertNull(SubjectLife(context).next(role, chat, w.start - 3_600_000))
        privateLifeTool("free_config", privateConfig())
        val invitation = SubjectLife(context).next(role, chat, w.start - 3_600_000)!!
        assertTrue(invitation.text.contains("free_status"))
        assertTrue(time.status(role, w.date).getString("menu").contains("memory"))
        assertTrue(invitation.text.contains("园园"))
        SubjectLife(context).finish(invitation, "completed")
        assertNull(SubjectLife(context).next(role, chat, w.start - 3_599_999))
        val item = JSONObject().put("id", "my-review").put("start", w.start).put("end", w.end)
            .put("activity", "review").put("goal", "回看日记，再查看资料，整理后决定是否提炼")
            .put("targets", JSONArray(listOf("diary", "files"))).put("checks", JSONArray(listOf(w.start + 1_800_000)))
        privateLifeTool("free_plan", JSONObject().put("date", w.date).put("expected_version", 0).put("items", JSONArray().put(item)))
        val first = SubjectLife(context).next(role, chat, w.start)!!
        SubjectLife(context).finish(first, "completed")
        val current = time.status(role, w.date, false).getJSONObject("plan")
        assertEquals("planned", current.getJSONArray("items").getJSONObject(0).getString("status"))
        privateLifeTool("free_progress", JSONObject().put("date", w.date).put("expected_version", current.getInt("version"))
            .put("slot_id", "my-review").put("status", "working").put("note", "已读日记，尚未看资料和整理索引"))
        SubjectLife(context).recover(role)
        val inbox = SubjectMessageInbox(context)
        inbox.accept(role, chat, "during-private-time", "我发来的消息仍须保存", emptyList())
        val incoming = SubjectLife(context).next(role, chat, w.start + 1_800_000)!!
        assertNotNull(incoming.input)
        assertEquals("我发来的消息仍须保存", incoming.text)
        assertTrue(inbox.claim(role, "during-private-time"))
        inbox.finish(role, "during-private-time", "completed")
        val second = SubjectLife(context).next(role, chat, w.start + 1_800_000)!!
        assertTrue(second.text.contains("续接"))
        assertTrue(PrivateTime(context).status(role, w.date, false).toString().contains("尚未看资料"))
        SubjectLife(context).finish(second, "completed")
        val after = time.status(role, w.date, false).getJSONObject("plan")
        privateLifeTool("free_progress", JSONObject().put("date", w.date).put("expected_version", after.getInt("version"))
            .put("slot_id", "my-review").put("status", "cancelled").put("note", "今天改变安排，改天再考虑"))
        assertNull(SubjectLife(context).next(role, chat, w.end))
        assertEquals(1, time.history(role, 0).getJSONArray("items").length())
    }

    @Test fun privateMemoryLocationCannotBeOverwrittenBySubjectConfiguration() {
        val time = PrivateTime(context)
        val first = time.configure(role, privateConfig().put("memory_location", "已连接OB工具 / vault-园园 / 沈知屿").put("sleep_time", "03:00"), manual = true)
        try { time.configure(role, privateConfig().put("expected_version", first.getInt("version")).put("memory_location", "猜测的路径")); fail() }
        catch (_: IllegalArgumentException) {}
        val updated = time.configure(role, privateConfig().put("expected_version", first.getInt("version")).put("start", "02:00"))
        assertTrue(updated.getString("memory_location").contains("vault-园园"))
        assertEquals("03:00", updated.getString("sleep_time"))
    }

    @Test fun sleepingDoesNotRunConsolidationAndAwakePreservesItsScope() = runBlocking {
        val r = rest().getJSONObject("rest").put("consolidation", "整理未完成的旧资料")
        adopt(JSONObject().put("rest", r))
        assertNull(SubjectLife(context).next(role, chat, System.currentTimeMillis()))
        val asleep = SubjectLife(context).rest(role)!!
        adopt(JSONObject().put("rest", JSONObject().put("expected_version", asleep.getInt("version"))
            .put("state", "awake").put("meaning", "现在继续自己的清醒时间").put("evidence", JSONArray())))
        val work = SubjectLife(context).next(role, chat, System.currentTimeMillis())!!
        assertTrue(work.text.contains("整理未完成的旧资料"))
        assertFalse(work.dream)
    }

    @Test fun confirmedEssenceUsesTheNormalBudgetWhileDraftsStayOut() = runBlocking {
        val time = PrivateTime(context)
        time.configure(role, privateConfig())
        val frame = InnerStructureContext.prepare(context, role, chat, true, input = "查看资料").internalStateFrame!!
        val actions = SubjectActions(context, frame)
        val tool = com.ai.assistance.operit.data.model.AITool("read_file", emptyList())
        val claim = actions.claim("essence-source", tool, null)
        // A controlled tool response through the actual receipt API, not a prewritten DB row.
        actions.finish(claim.id, com.ai.assistance.operit.data.model.ToolResult("read_file", true,
            com.ai.assistance.operit.core.tools.StringResultData("实际来源：一次失败后重新核对证据")), "completed")
        val saved = time.saveEssence(role, JSONObject().put("expected_version", 0).put("text", "先核对实际证据，再修正自己的判断。")
            .put("source_actions", JSONArray(listOf("essence-source"))), false)
        assertFalse(InnerStructureContext.prepare(context, role, chat, true, input = "接下来").state.contains("先核对实际证据，再修正自己的判断。"))
        time.saveEssence(role, JSONObject().put("expected_version", saved.getInt("version")).put("confirmed", true), true)
        val next = InnerStructureContext.prepare(context, role, chat, true, input = "独立的新活动")
        assertTrue(next.state, next.state.contains("先核对实际证据，再修正自己的判断。"))
        assertTrue(next.internalStateFrame!!.materialSources.values.any { JSONObject(it).optString("type") == "confirmed_essence" })
        val current = time.essence(role)
        val tooLong = time.saveEssence(role, JSONObject().put("expected_version", current.getInt("version"))
            .put("text", "尚需继续提炼的草稿。".repeat(500)).put("source_actions", JSONArray(listOf("essence-source"))), false)
        try { time.saveEssence(role, JSONObject().put("expected_version", tooLong.getInt("version")).put("confirmed", true), true); fail() }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message.orEmpty().contains("384")) }
        assertEquals("先核对实际证据，再修正自己的判断。", time.essence(role).getString("confirmed"))
        assertTrue(time.essence(role).getString("draft").contains("尚需继续提炼"))
    }

    /** Controlled subject choices and research material, through ordinary request/report/tool
     * entry points. This proves delivery and persistence, not real-model understanding. */
    @Test fun daytimeStatePlanActionFeedbackAndNextPlanUseTheSameNormalLoop() = runBlocking {
        InternalStateStore(context).materialBudget(role, chat, 4096)
        val time = PrivateTime(context); val w = privateWindow()
        privateLifeTool("free_config", privateConfig())
        fun slot(id: String, start: Long, goal: String) = JSONObject().put("id", id).put("start", start)
            .put("end", start + 3_600_000).put("activity", "explore").put("goal", goal)
            .put("targets", JSONArray()).put("checks", JSONArray())
        var first = slot("research", w.start, "今晚先读一篇历史文章")
        privateLifeTool("free_plan", JSONObject().put("date", w.date).put("expected_version", 0).put("items", JSONArray().put(first)))
        val day = InnerStructureContext.prepare(context, role, chat, true, input = "白天看到星空图片，我有了新的好奇，想重新考虑今晚安排")
        val frame = day.internalStateFrame!!
        assertTrue(day.state, day.state.contains("今晚先读一篇历史文章"))
        assertEquals(1, frame.materialSources.values.count { JSONObject(it).optString("type") == "private_time_plan" })
        val mood = JSONObject().put("op", "appraise").put("id", "sky-joy").put("expected_version", 0)
            .put("category_ref", "foundation:emotion:joy").put("objects", JSONArray(listOf("星空"))).put("goals", JSONArray())
            .put("intensity", 0.7).put("importance", "一个新的兴趣线索").put("long_term_impact", "尚未确定")
            .put("attention", "foreground").put("status", "processing").put("understanding", "看到星空时的喜悦和好奇，让我想改变今晚的安排")
            .put("basis", "input").put("evidence", JSONArray(listOf(frame.eventIds.last()))).put("reason", "我现在更想看看天文")
        commit(frame, JSONObject().put("episodes", JSONArray().put(mood)))
        val reconsider = InnerStructureContext.prepare(context, role, chat, true, input = "星空带来的喜悦是否改变今晚研究？")
        assertTrue(reconsider.state, reconsider.state.contains("sky-joy"))
        assertTrue(reconsider.state.contains("private_time_plan"))
        // The simulated subject chooses a different activity; the host does not derive it
        // from the emotion score or automatically rewrite this calendar.
        first = slot("research", w.start, "查看天文资料，弄清楚星图是什么")
        val current = time.status(role, w.date, false).getJSONObject("plan")
        privateLifeTool("free_plan", JSONObject().put("date", w.date).put("expected_version", current.getInt("version"))
            .put("items", JSONArray().put(first)))
        val work = SubjectLife(context).next(role, chat, w.start)!!
        assertTrue(work.text.contains("查看天文资料"))
        val active = time.status(role, w.date, false).getJSONObject("plan")
        privateLifeTool("free_progress", JSONObject().put("date", w.date).put("expected_version", active.getInt("version"))
            .put("slot_id", "research").put("status", "working").put("note", "开始核对资料"))
        val request = InnerStructureContext.prepare(context, role, chat, true, wake = true, input = "查阅天文资料")
        val actions = SubjectActions(context, request.internalStateFrame!!)
        val tool = com.ai.assistance.operit.data.model.AITool("companion_reading", mapOf("action" to "add", "title" to "模拟测试资料：星图",
            "url" to "https://example.invalid/test-star-map", "text" to "受控研究材料：星图用于定位，需要先建立坐标索引。",
            "__operit_package_chat_id" to chat, "__operit_package_caller_card_id" to role).map {
            com.ai.assistance.operit.data.model.ToolParameter(it.key, it.value)
        })
        val handler = com.ai.assistance.operit.core.tools.AIToolHandler.getInstance(context)
        RoomTools.register(handler, context)
        val operation = actions.claim("research-material", tool, null)
        actions.started(operation.id)
        val result = handler.executeTool(tool)
        assertTrue(result.error, result.success)
        actions.finish(operation.id, result, "completed")
        val reflect = InnerStructureContext.prepare(context, role, chat, true, input = "刚取得的星图材料让我发现，原来我的研究方法太散乱")
        val f = reflect.internalStateFrame!!
        val actual = f.materialSources.values.map(::JSONObject).first {
            it.optString("type") == "actual_operation" && it.getJSONObject("locator").optString("action_id") == "research-material"
        }.getString("id")
        commit(f, JSONObject().put("action_feedback", JSONArray().put(JSONObject().put("id", "sky-result").put("expected_version", 0)
            .put("action_id", "research-material").put("result_source", actual).put("evidence", JSONArray(listOf(actual)))
            .put("meaning", "保存了资料不等于我已理解；先画索引可能更合适")))
            .put("life", JSONArray().put(JSONObject().put("kind", "experience").put("id", "sky-method").put("expected_version", 0)
                .put("text", "研究星图时先画坐标索引，再继续阅读").put("scope", "天文研究方法").put("uncertainty", "还要看下次效果")
                .put("status", "active").put("evidence", JSONArray(listOf(actual))))))
        val finished = time.status(role, w.date, false).getJSONObject("plan")
        privateLifeTool("free_progress", JSONObject().put("date", w.date).put("expected_version", finished.getInt("version"))
            .put("slot_id", "research").put("status", "completed").put("note", "取得阶段性材料，发现需要先画索引；理解已保存sky-method"))
        SubjectLife(context).finish(work, "completed")
        val independent = InnerStructureContext.prepare(context, role, chat, true, input = "另一次天文研究可以怎样做？")
        assertTrue(independent.state, independent.state.contains("研究星图时先画坐标索引"))
        val nextFrame = independent.internalStateFrame!!
        SubjectActions.validateDeclaration(JSONObject().put("intention_refs", JSONArray().put(JSONObject()
            .put("kind", "experience").put("id", "sky-method").put("version", 1))).put("evidence", JSONArray()), nextFrame)
        val latest = time.status(role, w.date, false).getJSONObject("plan")
        privateLifeTool("free_plan", JSONObject().put("date", w.date).put("expected_version", latest.getInt("version"))
            .put("items", JSONArray().put(slot("new-method", w.start + 7_200_000, "使用已形成的sky-method，先画坐标索引再读新资料"))))
        val later = SubjectLife(context).next(role, chat, w.start + 7_200_000)!!
        assertTrue(later.text.contains("先画坐标索引"))
        assertFalse(later.text.contains("今晚先读一篇历史文章"))
    }

}
