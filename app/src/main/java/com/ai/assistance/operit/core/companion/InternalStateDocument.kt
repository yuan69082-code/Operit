package com.ai.assistance.operit.core.companion

import org.json.JSONObject

/** Read-only host port. Never serialized, copied into model output, or used as another writer. */
internal interface InternalStateHistoryAccess {
    fun definition(id: String, version: Int): JSONObject?
    fun latestContact(chat: String?, at: Long): Long?
    fun contacts(chat: String?, after: Long, until: Long): Sequence<Long>
}

internal class InternalStateDocument(data: JSONObject, val history: InternalStateHistoryAccess) : JSONObject(data.toString()) {
    companion object {
        fun copy(data: JSONObject): JSONObject = if (data is InternalStateDocument) InternalStateDocument(data, data.history)
            else JSONObject(data.toString())
    }
}
