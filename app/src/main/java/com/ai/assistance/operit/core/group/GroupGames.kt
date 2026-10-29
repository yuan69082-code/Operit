package com.ai.assistance.operit.core.group

import com.ai.assistance.operit.core.chat.RuntimeContextSections
import android.content.Context
import com.google.gson.Gson
import org.json.JSONArray
import org.json.JSONObject

class GroupGames(context: Context) {
    private val store = GroupStore(context)
    private val gson = Gson()
    fun state(chat: String): GroupGameState? = store.read(chat).optJSONObject("game")?.let {
        gson.fromJson(it.toString(), GroupGameState::class.java)
    }
    fun start(chat: String, kind: String, players: List<String>, rules: String): GroupGameState {
        val game = GroupGameEngine.create(kind, players, rules)
        store.update(chat) {
            check(it.optBoolean("group_exists", true)) { "群已结束，不能开始游戏" }
            val existing = it.optJSONObject("game")
            check(existing == null || existing.optString("phase") == "ended") { "请先结束当前游戏" }
            it.put("game", JSONObject(gson.toJson(game)))
        }
        store.log(chat, "游戏", "开始 $kind，${players.size} 位玩家")
        return game
    }
    fun end(chat: String) {
        store.update(chat) { it.optJSONObject("game")?.put("phase", "ended") }
        store.log(chat, "游戏", "本局已结束，游戏身份不再适用")
    }
    fun template(title: String, rules: String): JSONObject {
        require(title.isNotBlank() && title.length <= 80 && rules.isNotBlank() && rules.length <= 4000)
        val value = JSONObject().put("title", title).put("rules", rules)
        store.update("game_templates") {
            val templates = it.optJSONObject("templates") ?: JSONObject()
            require(templates.has(title) || templates.length() < 100) { "最多保存100个模板" }
            templates.put(title, value); it.put("templates", templates)
        }
        return value
    }
    fun templates(): List<Pair<String,String>> {
        val values = store.read("game_templates").optJSONObject("templates") ?: return emptyList()
        return values.keys().asSequence().map { it to values.getJSONObject(it).getString("rules") }.toList()
    }
    fun pending(chat: String): List<String>? {
        val s = state(chat) ?: return null
        if (s.phase == "ended") return null
        return if (s.kind == "werewolf") s.alive.filter { it !in s.actions }
        else listOf(s.players[s.turn])
    }
    fun act(chat: String, actor: String, move: JSONObject): JSONObject {
        store.update(chat) { value ->
            check(value.optBoolean("group_exists", true)) { "群已结束，不能继续游戏" }
            val s = value.optJSONObject("game")?.let { gson.fromJson(it.toString(), GroupGameState::class.java) } ?: error("尚未开局")
            check(actor in s.players) { "你不是本局玩家" }
            when(s.kind) {
                "landlord" -> if (s.phase == "bid") GroupGameEngine.bid(s, actor, move.getInt("bid"))
                    else GroupGameEngine.play(s, actor, if (move.optBoolean("pass")) emptyList() else {
                        val cards = move.getJSONArray("cards"); (0 until cards.length()).map { cards.getInt(it) }
                    })
                "werewolf" -> GroupGameEngine.werewolf(s, actor, if (move.optBoolean("skip")) "" else move.optString("target"), move.optString("text"))
                "story", "custom" -> GroupGameEngine.narrative(s, actor, move.optString("text"), move.optString("hidden"))
                else -> error("未知游戏")
            }
            if (s.events.size > 100) s.events.subList(0, s.events.size-100).clear()
            value.put("game", JSONObject(gson.toJson(s)))
        }
        // Tool transcripts are public group history. Never return hands, roles or secret feedback here.
        return view(chat, actor, privateView = false)
    }
    fun view(chat: String, actor: String, privateView: Boolean = true): JSONObject {
        val s = state(chat) ?: return JSONObject().put("active", false)
        check(actor in s.players) { "你不是本局玩家" }
        val result = JSONObject().put("kind", s.kind).put("phase", s.phase).put("round", s.round)
            .put("players", JSONArray(s.players)).put("winner", s.winner).put("rules", s.rules)
            .put("events", JSONArray(s.events.takeLast(12)))
            .put("your_action_pending", actor in pending(chat).orEmpty())
        when(s.kind) {
            "landlord" -> {
                result.put("turn", s.players[s.turn]).put("landlord", s.landlord).put("high_bid", s.highBid)
                    .put("last_cards", JSONArray(s.lastCards.map { GroupGameEngine.label(it) }))
                    .put("remaining", JSONObject(s.hands.mapValues { it.value.size }))
                if (privateView) result.put("your_hand", JSONArray(s.hands.getValue(actor).map {
                    JSONObject().put("id", it).put("label", GroupGameEngine.label(it))
                }))
            }
            "werewolf" -> {
                result.put("alive", JSONArray(s.alive)).put("submitted_count", s.actions.size)
                if (privateView) result.put("your_role", s.roles.getValue(actor)).put("your_private_findings", s.hidden[actor].orEmpty())
                if (s.phase == "ended") result.put("revealed_roles", JSONObject(s.roles))
            }
            else -> {
                result.put("turn", s.players[s.turn])
                // Ported ISC visibility function: public material plus only the requesting player's hidden material.
                val view = OtherworldVisibility.assemble(s.events.takeLast(8).joinToString("\n"),
                    s.hidden.mapValues { OtherworldVisibility.Hidden(ownReply = it.value) }, actor)
                result.put("public_scene", view.publicText)
                if (privateView) result.put("your_hidden_actions", view.hiddenText)
            }
        }
        return result
    }
    fun prompt(chat: String, actor: String): String = promptSections(chat, actor).let {
        if (it.state.isEmpty()) "" else it.asText()
    }

    fun promptSections(chat: String, actor: String): RuntimeContextSections {
        val game = state(chat)
        val visibleState = when {
            game == null -> ""
            game.phase == "ended" -> "\n本群上一局游戏已结束。\n"
            actor !in game.players -> "\n本群正在游戏，你是观众。\n"
            else -> "\n[本局游戏的个人可见状态]\n" + view(chat, actor).toString() + "\n"
        }
        return RuntimeContextSections(
            instructions = "\n以下游戏行动规则仅适用于本轮正在进行的游戏，不限制普通群聊。观众不可读取玩家私密信息；参与者的个人可见状态仅供本人使用，不要复述私密身份或手牌。" +
                "游戏身份不是日常身份；不把虚构剧情当作普通聊天事实存入记忆。只通过 group_game 行动，不借其他工具获取隐藏答案。" +
                "轮到你时使用 group_game act 提交行动；狼人杀 discussion 用 text 发表推理，night/vote 用 target 或 skip。" +
                "没有轮到你或已提交则用 group_chat quiet，不重复行动。斗地主不要 invent 牌ID，使用 your_hand 中的整数ID。" +
                "游戏结束后游戏身份不再适用；只能作为一起玩过的经历，不改变日常身份。\n",
            state = visibleState
        )
    }
}

