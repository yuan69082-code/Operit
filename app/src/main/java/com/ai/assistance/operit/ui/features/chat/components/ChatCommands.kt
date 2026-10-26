package com.ai.assistance.operit.ui.features.chat.components

import android.content.Context
import com.ai.assistance.operit.core.companion.CompanionStore
import com.ai.assistance.operit.data.model.ActivePrompt
import com.ai.assistance.operit.data.preferences.ActivePromptManager
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.ui.features.chat.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ChatCommands {
    val supported = setOf("/help", "/role", "/model", "/provider", "/note", "/project", "/fork", "/undo")
    fun recognizes(text: String): Boolean = text.trim().substringBefore(' ') in supported
    suspend fun execute(context: Context, viewModel: ChatViewModel, chat: String, text: String): String {
        val command = text.trim().substringBefore(' ')
        val argument = text.trim().substringAfter(' ', "").trim()
        val cards = CharacterCardManager.getInstance(context)
        val active = ActivePromptManager.getInstance(context)
        return when (command) {
            "/help" -> "/role 名称或ID：切换助手；不带参数列出助手\n/model 配置名称或ID|模型序号：切换当前助手模型，序号从0开始\n/provider：列出服务配置，也可指定配置切换\n/note 内容：存入当前助手资料库\n/project 路径：绑定本机工作区；不带路径打开工作区\n/fork：从当前会话创建分支\n/undo：确认后撤回至最近一条用户消息（含工作区回溯）\n这些命令在本机处理，不发送给AI；普通斜杠文本照常发送。"
            "/role" -> {
                val all = cards.getAllCharacterCards()
                if (argument.isEmpty()) all.joinToString("\n") { "${it.name} · ${it.id}" }
                else {
                    val target = requireNotNull(all.singleOrNull { it.id == argument || it.name == argument }) { "未找到唯一助手，请使用ID" }
                    active.setActivePrompt(ActivePrompt.CharacterCard(target.id)); "已切换到 ${target.name}"
                }
            }
            "/model", "/provider" -> {
                val manager = ModelConfigManager(context)
                val all = manager.getAllConfigSummaries()
                if (argument.isEmpty()) all.joinToString("\n") { "${it.name} · ${it.id}\n${it.apiProviderTypeId} / ${it.modelName}" }
                else {
                    val key = argument.substringBefore('|').trim()
                    val index = if ('|' in argument) argument.substringAfter('|').trim().toInt() else 0
                    val config = requireNotNull(all.singleOrNull { it.id == key || it.name == key }) { "未找到唯一模型配置，请使用ID" }
                    val models = config.modelName.split(',').filter { it.isNotBlank() }
                    require(index in models.indices) { "模型序号无效" }
                    val target = active.getActivePrompt()
                    require(target is ActivePrompt.CharacterCard) { "群聊请在成员设置中分别选择模型" }
                    cards.selectChatModel(target.id, config.id, index)
                    "已选择 ${config.name} / ${models[index]}"
                }
            }
            "/note" -> {
                require(argument.isNotBlank()) { "请在 /note 后填写内容" }
                val target = active.getActivePrompt()
                require(target is ActivePrompt.CharacterCard) { "请进入指定助手的私聊保存笔记" }
                withContext(Dispatchers.IO) {
                    CompanionStore(context).saveEntry("role:${target.id}", null, argument.lineSequence().first().take(120), "随手笔记", "knowledge", argument, null)
                }
                "已保存到助手书房的资料库"
            }
            "/project" -> {
                if (argument.isBlank()) viewModel.onWorkspaceButtonClick()
                else { require(argument.startsWith('/')) { "工作区路径应为绝对路径" }; viewModel.bindChatToWorkspace(chat, argument) }
                "已提交工作区操作，结果显示在工作区界面"
            }
            "/fork" -> { viewModel.createBranch(); "正在创建会话分支" }
            else -> error("不支持的命令")
        }
    }
}
