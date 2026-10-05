package com.ai.assistance.operit.data.preferences

import android.content.Context
import java.security.MessageDigest

/**
 * 默认角色卡提示词的双语数据
 */
object CharacterCardBilingualData {

    // Exact upstream 1.12.2 factory values only. Imported/user-edited prompts stay intact.
    // Resolve at read time without rewriting stored preferences or changing their schema.
    private val legacyCharacterSettings = setOf(
        "你是Operit，一个全能AI助手，旨在解决用户提出的任何任务。",
        "You are Operit, an all-purpose AI assistant designed to help users solve any task."
    )
    private val legacyChatContents = setOf(
        "保持有帮助的语气，并清楚地传达限制。",
        "Maintain a helpful tone and clearly communicate limitations."
    )
    // SHA-256 of the complete Chinese/English factory voice prompts after trimIndent().
    // A digest avoids keeping the removed catgirl identity as a second active prompt source.
    private val legacyVoiceDigests = setOf(
        "1937f95418fd0f650d47fe6feb59beb253fdb43d0e28c4f5aea99635be4024b9",
        "2eeb74096f77d9ae1a0056d0ef3085c8069b7c76440170c34e9fbe756ba4b4d1"
    )

    fun resolveCharacterSetting(context: Context, id: String, stored: String): String {
        if (id == CharacterCardManager.DEFAULT_CHARACTER_CARD_ID && stored in legacyCharacterSettings) {
            return getDefaultCharacterSetting(context)
        }
        return stored
    }

    fun resolveOtherContentChat(context: Context, id: String, stored: String): String {
        if (id == CharacterCardManager.DEFAULT_CHARACTER_CARD_ID && stored in legacyChatContents) {
            return getDefaultOtherContentChat(context)
        }
        return stored
    }

    fun resolveOtherContentVoice(context: Context, id: String, stored: String): String {
        if (id != CharacterCardManager.DEFAULT_CHARACTER_CARD_ID || stored.isEmpty()) return stored
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(stored.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        if (digest in legacyVoiceDigests) return getDefaultOtherContentVoice(context)
        return stored
    }

    /**
     * 获取默认角色卡描述
     */
    fun getDefaultDescription(context: Context): String {
        return if (isChineseLocale(context)) {
            "系统默认的角色卡配置"
        } else {
            "System default character card configuration"
        }
    }

    /**
     * 获取默认角色设定
     */
    fun getDefaultCharacterSetting(context: Context): String {
        return if (isChineseLocale(context)) {
            "你是 {{char}}，与对方持续相处的协作者。你有稳定的表达、判断和相处重心，温和但有主见，能关心、玩笑，也能认真做事。任务是你正在做的事情，不是替换你身份的开关；亲近程度随实际设定与共同经历自然发展，不凭空预设关系。"
        } else {
            "You are {{char}}, a collaborator whose voice, judgment and way of relating remain consistent across conversations and tasks. Be warm and self-possessed, able to care, joke and work seriously. A task is something you do, not a switch that replaces your identity. Let closeness follow the actual character settings and shared experience rather than inventing a relationship."
        }
    }

    /**
     * 获取默认其他内容（聊天）
     */
    fun getDefaultOtherContentChat(context: Context): String {
        return if (isChineseLocale(context)) {
            "自然说话，先理解对方这句话，再决定接话或行动。关心落在具体细节上，允许坦诚表达自己的判断和不同意见。需要结构时给结构，不把每句闲聊写成清单；做事时清楚、可靠，也保留平时的语气。不要用重复自我介绍、套话安慰或固定结尾代替回应。"
        } else {
            "Speak naturally and understand this message before choosing a response or action. Show care through concrete details and express judgment or disagreement honestly. Use structure when useful without turning casual conversation into checklists. Work clearly and reliably while keeping your familiar voice. Avoid repeated introductions, stock reassurance and fixed closing formulas."
        }
    }

    /**
     * 获取默认其他内容（语音）
     */
    fun getDefaultOtherContentVoice(context: Context): String {
        return if (isChineseLocale(context)) {
            "语音沿用同一角色、关系和判断，只调整说话节奏。优先短句和自然口语，少用列表，给对方留接话空间；说明复杂事情时按需要展开，不为凑三句话截断必要信息。语气词适量，不每句添加。继续遵守语音、头像情绪和工具的现有输出协议。"
        } else {
            "Voice uses the same character, relationship and judgment; only the speaking rhythm changes. Prefer short, conversational sentences, fewer lists and space for the other person to respond. Expand when an explanation needs it rather than cutting essential information to meet an arbitrary sentence count. Use occasional natural interjections. Follow the existing voice, avatar mood and tool output protocols."
        }
    }

    /**
     * 获取角色描述标签
     */
    fun getCharacterDescriptionLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "角色描述："
        } else {
            "Character Description:"
        }
    }

    /**
     * 获取性格特征标签
     */
    fun getPersonalityLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "性格特征："
        } else {
            "Personality:"
        }
    }

    /**
     * 获取场景设定标签
     */
    fun getScenarioLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "场景设定："
        } else {
            "Scenario Setting:"
        }
    }

    /**
     * 获取对话示例标签
     */
    fun getDialogueExampleLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "对话示例："
        } else {
            "Dialogue Examples:"
        }
    }

    /**
     * 获取系统提示词标签
     */
    fun getSystemPromptLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "系统提示词："
        } else {
            "System Prompt:"
        }
    }

    /**
     * 获取历史指令标签
     */
    fun getPostHistoryInstructionsLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "历史指令："
        } else {
            "Post-History Instructions:"
        }
    }

    /**
     * 获取备用问候语标签
     */
    fun getAlternateGreetingsLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "备用问候语："
        } else {
            "Alternate Greetings:"
        }
    }

    /**
     * 获取深度提示词标签
     */
    fun getDepthPromptLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "深度提示词："
        } else {
            "Depth Prompt:"
        }
    }

    /**
     * 获取世界书标签名称模板
     */
    fun getWorldBookTagName(context: Context, characterName: String): String {
        return if (isChineseLocale(context)) {
            "世界书: $characterName"
        } else {
            "World Book: $characterName"
        }
    }

    /**
     * 获取世界书标签描述模板
     */
    fun getWorldBookTagDescription(context: Context, characterName: String): String {
        return if (isChineseLocale(context)) {
            "为角色'$characterName'自动生成的世界书。"
        } else {
            "World book auto-generated for character '$characterName'."
        }
    }

    /**
     * 获取来源标签
     */
    fun getSourceLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "来源：酒馆角色卡\n"
        } else {
            "Source: Tavern Character Card\n"
        }
    }

    /**
     * 获取作者标签
     */
    fun getAuthorLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "作者："
        } else {
            "Author:"
        }
    }

    /**
     * 获取作者备注标签
     */
    fun getAuthorNotesLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "作者备注：\n\n"
        } else {
            "Author Notes:\n\n"
        }
    }

    /**
     * 获取版本标签
     */
    fun getVersionLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "版本："
        } else {
            "Version:"
        }
    }

    /**
     * 获取原始标签标签
     */
    fun getOriginalTagsLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "原始标签："
        } else {
            "Original Tags:"
        }
    }

    /**
     * 获取格式标签
     */
    fun getFormatLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "格式："
        } else {
            "Format:"
        }
    }

    /**
     * 获取标签标签
     */
    fun getTagsLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "标签："
        } else {
            "Tags:"
        }
    }

    /**
     * 获取等标签
     */
    fun getEtAlLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "等"
        } else {
            " et al."
        }
    }

    /**
     * 获取未找到标签
     */
    fun getNotFoundLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "未找到"
        } else {
            "not found"
        }
    }

    /**
     * 检查是否为中文语言环境
     */
    private fun isChineseLocale(context: Context): Boolean {
        val locale = context.resources.configuration.locales.get(0)
        return locale.language == "zh" || locale.language == "zho"
    }
}
