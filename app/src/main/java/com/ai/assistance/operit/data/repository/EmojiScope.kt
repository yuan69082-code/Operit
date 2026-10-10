package com.ai.assistance.operit.data.repository

import com.ai.assistance.operit.data.model.ActivePrompt

/** Null explicitly identifies the user's collection; published AI storage names remain stable. */
internal object EmojiScope {
    fun directory(target: ActivePrompt?): String = when (target) {
        null -> "user"
        is ActivePrompt.CharacterCard -> "character_card_${target.id}"
        is ActivePrompt.CharacterGroup -> "character_group_${target.id}"
    }
    fun preferencePrefix(target: ActivePrompt?): String = when (target) {
        null -> "user_custom_emoji_"
        is ActivePrompt.CharacterCard -> "character_card_custom_emoji_${target.id}_"
        is ActivePrompt.CharacterGroup -> "character_group_custom_emoji_${target.id}_"
    }
}
