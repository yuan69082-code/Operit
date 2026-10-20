package com.ai.assistance.operit.core.engine

/** A disconnected window remains associated: it must never silently impersonate its subject. */
object EngineWindowPolicy {
    val grants = setOf("messages", "group", "phone", "software", "call", "helpers")
    fun grant(kind: String): String = when {
        kind == "message.user" -> "messages"
        kind.startsWith("group.") -> "group"
        kind.startsWith("phone.") -> "phone"
        kind.startsWith("software.") -> "software"
        kind.startsWith("call.") -> "call"
        kind.startsWith("helper.") -> "helpers"
        else -> error("不支持的引擎事件类型")
    }
    fun maySubmit(associated: Boolean, enabled: Boolean, allowed: Set<String>, kind: String): Boolean =
        associated && enabled && grant(kind) in allowed
    fun usesLocalModel(associated: Boolean): Boolean = !associated
}
