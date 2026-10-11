package com.ai.assistance.operit.core.group

/*
 * Copyright (c) 2026 Otherworld Inn contributors
 * ISC. Full notice and original source: assets/licenses/otherworld-inn/.
 * Kotlin adaptation of server/lib/visibility.js at 147309bd7dbbe17222a9f4d6b5c7d4766507cc41.
 * Changed containers to typed maps; no implicit access to another player's hidden reply.
 */
object OtherworldVisibility {
    data class Hidden(val ownReply: String = "", val publicClue: String = "")
    data class View(val publicText: String, val hiddenText: String)
    fun assemble(publicReply: String, hiddenByPlayer: Map<String, Hidden>, player: String): View {
        val own = hiddenByPlayer[player]?.ownReply.orEmpty()
        val leaked = hiddenByPlayer.filterKeys { it != player }.values
            .map { it.publicClue }.filter { it.isNotBlank() && it != "无" }.joinToString(" ")
        return View(listOf(publicReply, leaked).filter { it.isNotBlank() }.joinToString(" "), own)
    }
}
