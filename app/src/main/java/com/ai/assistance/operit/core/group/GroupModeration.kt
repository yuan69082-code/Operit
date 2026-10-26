package com.ai.assistance.operit.core.group

import android.content.Context
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import org.json.JSONArray

class GroupModeration(private val context: Context) {
    private val store = GroupStore(context)
    fun manuallyMuted(chat: String, actor: String): Boolean {
        val state = store.read(chat)
        return !state.optBoolean("group_exists", true) || state.optBoolean("mute_all") || actor in store.strings(state.optJSONArray("muted"))
    }
    fun gameMuted(chat: String, actor: String): Boolean = gameMuted(GroupGames(context).state(chat), actor)
    fun mayPublish(chat: String, actor: String) = !manuallyMuted(chat, actor) && !gameMuted(chat, actor)

    suspend fun change(chat: String, operator: String?, members: Set<String>, targets: List<String>, all: Boolean, muted: Boolean) {
        check(operator == null || CharacterCardManager.getInstance(context).isPrimaryAssistant(operator)) { "只有用户或主助手可以管理禁言" }
        require(all || targets.isNotEmpty()) { "请选择禁言对象或全员" }
        require(targets.all { it in members || it == "user" }) { "禁言对象不在本群" }
        store.update(chat) { state ->
            if (all) {
                state.put("mute_all", muted)
                if (!muted) state.put("muted", JSONArray())
            } else {
                val previous = store.strings(state.optJSONArray("muted")).toSet()
                // While all are muted, individually unmuting materializes the remaining set.
                val base = if (state.optBoolean("mute_all")) members + "user" else previous
                state.put("mute_all", false).put("muted", JSONArray((if (muted) base + targets else base - targets.toSet()).toList()))
            }
        }
        store.log(chat, "禁言", "${if (muted) "禁言" else "解除禁言"}：${if (all) "全员" else targets.joinToString()}", operator ?: "user")
    }

    companion object {
        fun gameMuted(game: GroupGameState?, actor: String): Boolean {
            if (game == null || game.phase == "ended") return false
            if (actor !in game.players) return true
            return if (game.kind == "werewolf")
                actor !in game.alive || game.phase == "night" ||
                    (game.phase == "discussion" && actor in game.actions)
            else game.players[game.turn] != actor
        }
    }
}
