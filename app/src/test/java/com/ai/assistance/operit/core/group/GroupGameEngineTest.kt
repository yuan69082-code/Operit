package com.ai.assistance.operit.core.group

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class GroupGameEngineTest {
    @Test fun onlyTheOwnerOrPrimaryMayRewriteADirectedRelationship() {
        assertTrue(RelationshipPolicy.mayEdit("a", "a", false))
        assertFalse(RelationshipPolicy.mayEdit("b", "a", false))
        assertTrue(RelationshipPolicy.mayEdit("primary", "a", true))
        assertFalse(RelationshipPolicy.mayRead("c", "a", "b", false))
        assertTrue(RelationshipPolicy.mayRead("b", "a", "b", false))
        assertTrue(RelationshipPolicy.mayRead("primary", "a", "b", true))
    }
    private val players = listOf("user", "a", "b")
    @Test fun deckIsCompleteAndEachPlayerHasDistinctCards() {
        val game = GroupGameEngine.create("landlord", players, "", Random(11))
        assertEquals(listOf(17,17,17), players.map { game.hands.getValue(it).size })
        assertEquals((0..53).toSet(), (game.hands.values.flatten() + game.bottom).toSet())
        assertEquals(54, (game.hands.values.flatten()+game.bottom).size)
    }
    @Test fun biddingCannotSkipThePlayerOrUnderbidAndAddsOnlyBottomCards() {
        val game = GroupGameEngine.create("landlord", players, "", Random(3))
        assertThrows(IllegalStateException::class.java) { GroupGameEngine.bid(game, "a", 3) }
        GroupGameEngine.bid(game, "user", 2)
        assertThrows(IllegalArgumentException::class.java) { GroupGameEngine.bid(game, "a", 1) }
        GroupGameEngine.bid(game, "a", 3)
        assertEquals("play", game.phase); assertEquals("a", game.landlord)
        assertEquals(20, game.hands.getValue("a").size)
    }
    @Test fun invalidCardsAndLeadingPassDoNotMutateState() {
        val game = GroupGameEngine.create("landlord", players, "", Random(7))
        GroupGameEngine.bid(game, "user", 3)
        val hand = game.hands.getValue("user").toList()
        assertThrows(IllegalArgumentException::class.java) { GroupGameEngine.play(game, "user", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { GroupGameEngine.play(game, "user", listOf(game.hands.getValue("a").first())) }
        assertThrows(IllegalArgumentException::class.java) { GroupGameEngine.play(game, "user", listOf(hand.first(), hand.first())) }
        assertEquals(hand, game.hands.getValue("user")); assertEquals(0, game.turn)
    }
    @Test fun twoPassesReturnLeadToLastPlayer() {
        val game = GroupGameEngine.create("landlord", players, "", Random(7))
        GroupGameEngine.bid(game, "user", 3)
        GroupGameEngine.play(game, "user", listOf(game.hands.getValue("user").first()))
        GroupGameEngine.play(game, "a", emptyList()); GroupGameEngine.play(game, "b", emptyList())
        assertEquals(0, game.turn); assertTrue(game.lastCards.isEmpty())
    }
    @Test fun bombsRocketAndSequencesFollowOrdering() {
        val bomb = GroupGameEngine.combo(listOf(0,1,2,3))
        val rocket = GroupGameEngine.combo(listOf(52,53))
        assertTrue(GroupGameEngine.beats(rocket, bomb))
        assertFalse(GroupGameEngine.beats(bomb, rocket))
        assertTrue(GroupGameEngine.beats(bomb, GroupGameEngine.combo(listOf(48))))
        assertEquals("straight", GroupGameEngine.combo(listOf(0,4,8,12,16)).type)
        assertEquals("pairs", GroupGameEngine.combo(listOf(0,1,4,5,8,9)).type)
        assertThrows(IllegalStateException::class.java) { GroupGameEngine.combo(listOf(32,36,40,44,48)) }
        assertFalse(GroupGameEngine.beats(GroupGameEngine.combo(listOf(4,5)), GroupGameEngine.combo(listOf(0))))
    }
    @Test fun planesAndFourWithWingsAreValidated() {
        assertEquals("plane", GroupGameEngine.combo(listOf(0,1,2,4,5,6)).type)
        assertEquals("plane_one", GroupGameEngine.combo(listOf(0,1,2,4,5,6,8,12)).type)
        assertEquals("plane_pair", GroupGameEngine.combo(listOf(0,1,2,4,5,6,8,9,12,13)).type)
        assertEquals("four_two", GroupGameEngine.combo(listOf(0,1,2,3,8,9)).type)
        assertThrows(IllegalStateException::class.java) { GroupGameEngine.combo(listOf(0,1,2,8,9,10)) }
    }
    @Test fun winningFarmerEndsTheRound() {
        val game = GroupGameEngine.create("landlord", players, "", Random(1))
        GroupGameEngine.bid(game, "user", 3)
        game.turn = 1; game.hands["a"] = mutableListOf(52)
        GroupGameEngine.play(game, "a", listOf(52))
        assertEquals("农民", game.winner); assertEquals("ended", game.phase)
        assertThrows(IllegalStateException::class.java) { GroupGameEngine.play(game, "b", emptyList()) }
    }
    @Test fun seerFindingsStayPrivateAndNightCannotBeSubmittedTwice() {
        val ids = listOf("user","a","b","c","d","e")
        val game = GroupGameEngine.create("werewolf", ids, "", Random(1))
        val seer = game.roles.entries.single { it.value == "预言家" }.key
        val wolf = game.roles.entries.first { it.value == "狼人" }.key
        GroupGameEngine.werewolf(game, seer, wolf)
        assertTrue(game.hidden.getValue(seer).contains("狼人"))
        assertTrue(game.events.none { it.contains("查验") })
        assertThrows(IllegalArgumentException::class.java) { GroupGameEngine.werewolf(game, seer, wolf) }
    }
    @Test fun guardedTargetSurvivesAndVotesWaitForAllPlayers() {
        val ids = listOf("user","a","b","c","d","e")
        val game = GroupGameEngine.create("werewolf", ids, "", Random(1))
        val victim = game.roles.entries.first { it.value == "村民" }.key
        ids.forEach { actor -> GroupGameEngine.werewolf(game, actor, if (game.roles[actor] in setOf("狼人","守卫")) victim else "") }
        assertEquals(ids, game.alive); assertEquals("discussion", game.phase)
        ids.forEach { GroupGameEngine.werewolf(game, it, "", "讨论") }
        val wolf = game.roles.entries.first { it.value == "狼人" }.key
        ids.dropLast(1).forEach { GroupGameEngine.werewolf(game, it, if (it == wolf) "" else wolf) }
        assertTrue(wolf in game.alive)
        GroupGameEngine.werewolf(game, ids.last(), if (ids.last() == wolf) "" else wolf)
        assertEquals("好人", game.winner)
    }
    @Test fun hiddenStoryActionsStayOutOfPublicEvents() {
        val game = GroupGameEngine.create("story", players, "探索小屋")
        GroupGameEngine.narrative(game, "user", "查看门口", "将钥匙藏起来")
        assertTrue(game.events.none { it.contains("钥匙") })
        assertEquals("\n将钥匙藏起来", game.hidden["user"])
        val view = OtherworldVisibility.assemble("门口", mapOf("user" to OtherworldVisibility.Hidden(game.hidden.getValue("user"))), "a")
        assertEquals("", view.hiddenText); assertEquals("门口", view.publicText)
    }
    @Test fun publicCluesAreSharedWithoutSharingThePrivateReply() {
        val views = mapOf("a" to OtherworldVisibility.Hidden("密码123", "门开了"), "b" to OtherworldVisibility.Hidden("我的线索"))
        val b = OtherworldVisibility.assemble("大厅", views, "b")
        assertEquals("我的线索", b.hiddenText); assertEquals("大厅 门开了", b.publicText)
        assertFalse(b.toString().contains("123"))
    }
    @Test fun routingAndHistoricalReadBoundariesKeepAssistantsIsolated() {
        assertEquals(listOf("a","b"), GroupPolicy.targets(listOf("a","b"), emptyList()))
        assertEquals(listOf("b", "a"), GroupPolicy.targets(listOf("a","b"), listOf("b")))
        assertThrows(IllegalArgumentException::class.java) { GroupPolicy.targets(listOf("a"), listOf("b")) }
        assertFalse(GroupPolicy.canRead("b", "a", false, 0, 0, 1))
        assertTrue(GroupPolicy.canRead("a", "a", false, 0, 0, 1))
        assertTrue(GroupPolicy.canRead(null, "a", true, 10, 20, 15))
        assertFalse(GroupPolicy.canRead(null, "a", true, 10, 20, 21))
        assertFalse(GroupPolicy.canRead(null, "a", true, 10, 20, 9))
    }
}
