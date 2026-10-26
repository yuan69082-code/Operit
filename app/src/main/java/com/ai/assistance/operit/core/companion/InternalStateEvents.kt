package com.ai.assistance.operit.core.companion

/** Only host-confirmed results enter these mappings; the model cannot call an event endpoint. */
object InternalStateEvents {
    fun toolSource(name: String, action: String?, status: String?, success: Boolean): String? = when {
        name == "companion_reading" && success && action in setOf("get", "fetch", "note", "progress") -> "reading"
        name == "companion_todo" && !success -> "todo_failed"
        name == "companion_todo" && success && (status == "done" || action == "distill") -> "todo_completed"
        name == "companion_todo" && success && action in setOf("save", "comment", "submit") -> "todo_progress"
        else -> null
    }

    fun actors(group: Boolean, user: Boolean, privateRole: String?, members: List<String>, author: String?): List<String> =
        if (!group) listOfNotNull(privateRole) else if (user) members.distinct() else listOfNotNull(author)
}
