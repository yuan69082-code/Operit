package com.ai.assistance.operit.core.companion

import android.content.Context
import com.ai.assistance.operit.data.model.ChatHistory
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray

/** User-selected destinations only. Background writes must never move an assistant's inbox. */
object AssistantWindowRouter {
    private val migrationLock = Mutex()
    private const val VERSION = "assistant_routing_v1"
    private fun key(role: String) = "assistant_window:$role"
    private fun recentKey(role: String) = "assistant_windows:$role"

    fun chat(context: Context, role: String?): String? = role?.let {
        CompanionStore(context).preferences.getString(key(it), null)
    }

    fun selected(context: Context, chat: ChatHistory) {
        val role = chat.characterCardId ?: return
        if (!chat.characterGroupId.isNullOrBlank() || role.startsWith("unassigned:")) return
        selected(context, role, chat.id)
    }

    fun selected(context: Context, role: String, chat: String) {
        if (role.isBlank() || role.startsWith("unassigned:")) return
        val prefs = CompanionStore(context).preferences
        if (prefs.getString(key(role), null) == chat) return
        // Cancel work using the old context before publishing the new destination.
        CompanionRuntime.noteUserInteraction()
        val recent = JSONArray(prefs.getString(recentKey(role), "[]"))
        val order = listOf(chat) + (0 until recent.length()).map { recent.getString(it) }.filter { it != chat }
        prefs.edit().putString(key(role), chat).putString(recentKey(role), JSONArray(order).toString()).apply()
    }

    suspend fun initialize(context: Context) = migrationLock.withLock {
        val store = CompanionStore(context)
        if (store.preferences.getBoolean(VERSION, false)) return@withLock
        val repo = ChatHistoryManager.getInstance(context)
        val histories = repo.getAllChatMetadata()
        val privateChats = histories.filter { it.characterCardId != null && it.characterGroupId.isNullOrBlank() }
        val edit = store.preferences.edit()
        // One-time adoption for installs without user-selection history.
        privateChats.groupBy { checkNotNull(it.characterCardId) }.forEach { (role, chats) ->
            val existing = JSONArray(store.preferences.getString(recentKey(role), "[]"))
            val known = (0 until existing.length()).map { existing.getString(it) }
            val sorted = chats.sortedByDescending { it.updatedAt }.map { it.id }
            val order = known.filter { it in sorted } + sorted.filter { it !in known }
            if (!store.preferences.contains(key(role))) edit.putString(key(role), order.first())
            edit.putString(recentKey(role), JSONArray(order).toString())
        }
        val selectedId = repo.getSelectedChatId()
        val selected = privateChats.firstOrNull { it.id == selectedId }
        if (selected != null) {
            val role = checkNotNull(selected.characterCardId)
            val order = listOf(selected.id) + privateChats.filter { it.characterCardId == role && it.id != selected.id }
                .sortedByDescending { it.updatedAt }.map { it.id }
            edit.putString(key(role), selected.id).putString(recentKey(role), JSONArray(order).toString())
        }
        val oldTarget = store.preferences.getString("target_chat", null)
        val oldRole = privateChats.firstOrNull { it.id == oldTarget }?.characterCardId
        if (!store.preferences.contains("target_role") && oldRole != null) {
            edit.putString("target_role", oldRole)
            val oldSchedule = store.read("schedule:$oldTarget")
            store.update("schedule:role:$oldRole") { data -> oldSchedule.keys().forEach { data.put(it, oldSchedule.get(it)) } }
        }
        edit.remove("target_chat").remove("phone_chat").remove("software_chat").putBoolean(VERSION, true).apply()
    }

    suspend fun removed(context: Context, chat: ChatHistory) {
        val role = chat.characterCardId ?: return
        if (this.chat(context, role) != chat.id) return
        val prefs = CompanionStore(context).preferences
        val recent = JSONArray(prefs.getString(recentKey(role), "[]"))
        val order = (0 until recent.length()).map { recent.getString(it) }.filter { it != chat.id }
        val privateIds = ChatHistoryManager.getInstance(context).getAllChatMetadata()
            .filter { it.characterCardId == role && it.characterGroupId.isNullOrBlank() }.map { it.id }.toSet()
        val remaining = order.filter { it in privateIds }
        CompanionRuntime.noteUserInteraction()
        val edit = prefs.edit().putString(recentKey(role), JSONArray(remaining).toString())
        if (remaining.isEmpty()) edit.remove(key(role)) else edit.putString(key(role), remaining.first())
        edit.apply()
    }
}
