package com.ai.assistance.operit.core.group

/** Assistant IDs, never model names, are the routing authority. */
object GroupPolicy {
    fun ownsTurn(actor: String?, author: String?): Boolean = actor != null && author != null && actor == author
    fun targets(memberIds: List<String>, selected: List<String>): List<String> {
        require(selected.all { it in memberIds }) { "@ 的助手已离开本群，请重新选择" }
        // Mentions indicate who the user is addressing, never who is allowed to hear or reply.
        return (selected + memberIds).distinct()
    }

    fun canRead(privateOwner: String?, actor: String, group: Boolean, first: Long, through: Long, at: Long): Boolean =
        if (!group) privateOwner == actor else first > 0 && at in first..through

    fun wakeAt(now: Long, minutes: Int): Long {
        require(minutes in 1..43200) { "唤醒时间须为 1–43200 分钟" }
        return now + minutes * 60_000L
    }
}

