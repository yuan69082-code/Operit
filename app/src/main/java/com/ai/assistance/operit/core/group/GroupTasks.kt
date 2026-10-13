package com.ai.assistance.operit.core.group

import android.content.Context
import com.ai.assistance.operit.core.tasks.TaskBoard
import org.json.JSONArray

class GroupTasks(private val context: Context) {
    fun list(chat: String): JSONArray = JSONArray(TaskBoard(context, chat, true).list())
}
