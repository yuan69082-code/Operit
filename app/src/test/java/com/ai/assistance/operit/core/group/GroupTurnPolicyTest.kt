package com.ai.assistance.operit.core.group

import org.junit.Assert.*
import org.junit.Test

class GroupTurnPolicyTest {
    @Test fun legacyPrefixIsRemovedWithoutLosingBodyOrQuotes() {
        val header = "[历史发言来源：克劳德 [助手ID=B]；这是你自己的发言]\n"
        val body = "园园，我看过了。正文引用：$header"
        assertEquals(body, GroupTurnPolicy.cleanLegacyPrefix(header + header + body))
        assertEquals(body, GroupTurnPolicy.cleanLegacyPrefix(body))
    }
    @Test fun cleanupIsIdempotentAndDoesNotGuessUnknownHeaders() {
        val text = "[历史发言来源：这是普通讨论的标题]\n保持原文"
        assertEquals(text, GroupTurnPolicy.cleanLegacyPrefix(text))
        val clean = GroupTurnPolicy.cleanLegacyPrefix("[历史发言来源：B 这是你自己的发言]正文")
        assertEquals("正文", clean)
        assertEquals(clean, GroupTurnPolicy.cleanLegacyPrefix(clean))
    }
    @Test fun endAndWaitingForUserCancelAlreadyQueuedSpeakers() {
        for (action in listOf("end", "wait_user"))
            assertTrue(GroupTurnPolicy.next(listOf("B", "C"), action, emptyList(), setOf("A", "B", "C"), "A", true).isEmpty())
    }
    @Test fun exhaustedQueueNeverAutomaticallyRestarts() {
        assertTrue(GroupTurnPolicy.next(emptyList(), null, emptyList(), setOf("A", "B"), "A", true).isEmpty())
        assertTrue(GroupTurnPolicy.next(emptyList(), "quiet", emptyList(), setOf("A", "B"), "A", true).isEmpty())
    }
    @Test fun ordinaryUserMessageStillOffersEveryoneOneTurn() {
        assertEquals(listOf("B", "C"), GroupTurnPolicy.next(listOf("B", "C"), null, emptyList(), setOf("A", "B", "C"), "A", false))
    }
    @Test fun directedTurnOnlyInvitesRecipientsAndDropsRemovedMembers() {
        assertEquals(listOf("B"), GroupTurnPolicy.next(listOf("B", "C"), "respond",
            listOf("B", "A", "gone", "B"), setOf("A", "B", "C"), "A", true))
    }
    @Test fun quietSuppressesPublicTextButWaitCanAddressUser() {
        assertTrue(GroupTurnPolicy.suppress("quiet"))
        assertFalse(GroupTurnPolicy.suppress("wait_user"))
        val text = "园园你觉得呢？<group_turn action=\"wait_user\"/>"
        assertEquals("wait_user", GroupTurnPolicy.control(text)?.action)
        assertEquals("园园你觉得呢？", GroupTurnPolicy.publicBody(text))
    }
    @Test fun controlTargetsAreAssistantIdsNotModelNames() {
        val control = GroupTurnPolicy.control("<group_turn action=\"respond\" targets=\"B, C\"/>")!!
        assertEquals(listOf("B", "C"), control.targets)
    }
    @Test fun gameSystemMuteEndsWithGameAndHidesDeadPlayers() {
        val game = GroupGameState("werewolf", listOf("A", "B", "user"), phase = "night")
        assertTrue(GroupModeration.gameMuted(game, "A"))
        game.phase = "discussion"
        assertFalse(GroupModeration.gameMuted(game, "A"))
        game.alive.remove("B")
        assertTrue(GroupModeration.gameMuted(game, "B"))
        game.phase = "ended"
        assertFalse(GroupModeration.gameMuted(game, "B"))
    }
    @Test fun cardGameOnlyCurrentPlayerMayPublish() {
        val game = GroupGameState("landlord", listOf("A", "B", "user"), phase = "play", turn = 1)
        assertFalse(GroupModeration.gameMuted(game, "B"))
        assertTrue(GroupModeration.gameMuted(game, "A"))
        assertTrue(GroupModeration.gameMuted(game, "spectator"))
    }

    @Test fun toolResultsAndQuotedExamplesCannotControlRouting() {
        val marker = "<group_turn action=\"end\"/>"
        assertNull(GroupTurnPolicy.control("<tool_result_x1 name='read_file'>$marker</tool_result_x1>"))
        assertNull(GroupTurnPolicy.control("```xml\n$marker\n```"))
        assertNull(GroupTurnPolicy.control("$marker 是这个标记的示例"))
        assertEquals("end", GroupTurnPolicy.control("事情结束了。$marker")?.action)
    }
}
