package com.ai.assistance.operit.core.group

import kotlin.random.Random

data class GroupGameState(
    val kind: String, val players: List<String>, val rules: String = "",
    var phase: String = "", var turn: Int = 0, var round: Int = 1, var winner: String = "",
    val hands: MutableMap<String, MutableList<Int>> = mutableMapOf(),
    val roles: MutableMap<String, String> = mutableMapOf(),
    val hidden: MutableMap<String, String> = mutableMapOf(),
    val alive: MutableList<String> = players.toMutableList(),
    val actions: MutableMap<String, String> = mutableMapOf(),
    val events: MutableList<String> = mutableListOf(),
    var bottom: List<Int> = emptyList(), var landlord: String = "", var highBid: Int = 0,
    var bidCount: Int = 0, var lastCards: List<Int> = emptyList(), var lastPlayer: String = "", var passes: Int = 0
)

/** Rules, deck ownership, visibility and winner are enforced locally, not by generated prose. */
object GroupGameEngine {
    data class Combo(val type: String, val size: Int, val high: Int)
    fun rank(card: Int): Int { require(card in 0..53); return if (card < 52) card / 4 + 3 else card - 36 }
    fun label(card: Int): String {
        val r = rank(card)
        val text = when(r) { 11 -> "J";12 -> "Q";13 -> "K";14 -> "A";15 -> "2";16 -> "小王";17 -> "大王";else -> r.toString() }
        return if (card < 52) "${listOf("♠", "♥", "♣", "♦")[card % 4]}$text" else text
    }
    private fun consecutive(values: List<Int>) = values.isNotEmpty() && values.last() <= 14 && values.zipWithNext().all { (a,b) -> b == a + 1 }
    fun combo(cards: List<Int>): Combo {
        require(cards.isNotEmpty() && cards.distinct().size == cards.size) { "请选不重复的牌" }
        val c = cards.map(::rank).groupingBy { it }.eachCount().toSortedMap()
        val n = cards.size
        if (c.keys.toList() == listOf(16,17)) return Combo("rocket", 2, 17)
        if (c.size == 1 && n <= 4) return Combo(if (n == 4) "bomb" else "same$n", n, c.firstKey())
        if (n == 4 && 3 in c.values) return Combo("triple_one", n, c.entries.first { it.value == 3 }.key)
        if (n == 5 && c.values.sorted() == listOf(2,3)) return Combo("triple_pair", n, c.entries.first { it.value == 3 }.key)
        if (n >= 5 && c.values.all { it == 1 } && consecutive(c.keys.toList())) return Combo("straight", n, c.lastKey())
        if (n >= 6 && n % 2 == 0 && c.values.all { it == 2 } && consecutive(c.keys.toList())) return Combo("pairs", n, c.lastKey())
        val triples = c.filter { it.value >= 3 && it.key <= 14 }.keys.toList()
        for (length in 2..triples.size) for (start in 0..triples.size-length) {
            val body = triples.subList(start, start+length)
            if (!consecutive(body)) continue
            val rest = c.toMutableMap()
            body.forEach { rest[it] = rest.getValue(it) - 3 }
            val wings = rest.filterValues { it > 0 }
            if (n == length*3 && wings.isEmpty()) return Combo("plane", n, body.last())
            if (n == length*4 && wings.values.sum() == length && body.none { it in wings } && !(16 in wings && 17 in wings)) return Combo("plane_one", n, body.last())
            if (n == length*5 && wings.size == length && wings.values.all { it == 2 }) return Combo("plane_pair", n, body.last())
        }
        val four = c.entries.firstOrNull { it.value == 4 }?.key
        if (four != null && n == 6) return Combo("four_two", n, four)
        if (four != null && n == 8 && c.filterKeys { it != four }.values.sorted() == listOf(2,2)) return Combo("four_pairs", n, four)
        error("这组牌不符合牌型规则")
    }
    fun beats(next: Combo, previous: Combo): Boolean = when {
        next.type == "rocket" -> previous.type != "rocket"
        previous.type == "rocket" -> false
        next.type == "bomb" && previous.type != "bomb" -> true
        else -> next.type == previous.type && next.size == previous.size && next.high > previous.high
    }
    fun create(kind: String, players: List<String>, rules: String, random: Random = Random.Default): GroupGameState {
        require(players.distinct().size == players.size && players.size >= 2)
        require(kind in setOf("landlord", "werewolf", "story", "custom"))
        require(rules.length <= 4000)
        val state = GroupGameState(kind, players, rules)
        when(kind) {
            "landlord" -> { require(players.size == 3) { "斗地主需要你和两位助手，共三位玩家" }; deal(state, random) }
            "werewolf" -> {
                require(players.size in 4..12) { "狼人杀支持 4–12 位玩家" }
                val wolves = maxOf(1, players.size / 4)
                val roles = (List(wolves) { "狼人" } + listOf("预言家", "守卫") + List(players.size-wolves-2) { "村民" }).shuffled(random)
                players.forEachIndexed { index, id -> state.roles[id] = roles[index] }
                state.phase = "night"; state.events.add("第1夜开始。每人提交夜间行动；村民提交跳过。")
            }
            else -> { require(rules.isNotBlank()) { "请填写场景、游戏规则与目标" }; state.phase = "playing"; state.events.add("游戏开始：$rules") }
        }
        return state
    }
    private fun deal(s: GroupGameState, random: Random) {
        val deck = (0..53).shuffled(random)
        s.players.forEachIndexed { i, p -> s.hands[p] = deck.subList(i*17, i*17+17).sorted().toMutableList() }
        s.bottom = deck.takeLast(3); s.phase = "bid"; s.highBid = 0; s.bidCount = 0; s.landlord = ""; s.turn = 0
        s.events.add("洗牌完成，按座位顺序叫分，可不叫，最高3分。")
    }
    fun bid(s: GroupGameState, actor: String, bid: Int) {
        check(s.kind == "landlord" && s.phase == "bid" && s.players[s.turn] == actor) { "还没轮到你叫分" }
        require(bid in 0..3 && (bid == 0 || bid > s.highBid)) { "叫分须高于已有叫分，或选不叫" }
        s.events.add("$actor：${if (bid == 0) "不叫" else "叫${bid}分"}")
        s.bidCount++
        if (bid > s.highBid) { s.highBid = bid; s.landlord = actor }
        if (bid == 3 || s.bidCount == 3) {
            if (s.highBid == 0) { s.round++; deal(s, Random.Default); return }
            s.hands.getValue(s.landlord).addAll(s.bottom); s.hands.getValue(s.landlord).sort()
            s.phase = "play"; s.turn = s.players.indexOf(s.landlord)
            s.events.add("${s.landlord} 成为地主；底牌：${s.bottom.joinToString { label(it) }}")
        } else s.turn = (s.turn+1)%3
    }
    fun play(s: GroupGameState, actor: String, cards: List<Int>) {
        check(s.kind == "landlord" && s.phase == "play" && s.players[s.turn] == actor) { "还没轮到你出牌" }
        val hand = s.hands.getValue(actor)
        if (cards.isEmpty()) {
            require(s.lastCards.isNotEmpty() && s.lastPlayer != actor) { "领出时不能不出" }
            s.events.add("$actor：不出"); s.passes++
            s.turn = (s.turn+1)%3
            if (s.passes == 2) { s.turn = s.players.indexOf(s.lastPlayer); s.lastCards = emptyList(); s.passes = 0 }
            return
        }
        require(cards.all { it in hand } && cards.distinct().size == cards.size) { "只能打出自己的手牌" }
        val type = combo(cards)
        if (s.lastCards.isNotEmpty()) require(beats(type, combo(s.lastCards))) { "牌型不匹配或未压过上一手" }
        hand.removeAll(cards.toSet()); s.lastCards = cards; s.lastPlayer = actor; s.passes = 0
        s.events.add("$actor：${cards.joinToString { label(it) }}")
        if (hand.isEmpty()) { s.winner = if (actor == s.landlord) "地主" else "农民"; s.phase = "ended"; s.events.add("${s.winner}获胜") }
        else s.turn = (s.turn+1)%3
    }
    fun werewolf(s: GroupGameState, actor: String, target: String, text: String = "") {
        check(s.kind == "werewolf" && s.phase != "ended" && actor in s.alive) { "你当前不能行动" }
        require(actor !in s.actions) { "本阶段已提交，请等待其他玩家" }
        require(target.isEmpty() || target in s.alive) { "目标不在存活玩家中" }
        when(s.phase) {
            "night" -> {
                val role = s.roles.getValue(actor)
                if (role == "村民") require(target.isEmpty()) { "村民夜间请选择跳过" }
                if (role == "狼人" && target.isNotEmpty()) require(s.roles[target] != "狼人") { "不能袭击同阵营" }
                if (role == "预言家" && target.isNotEmpty()) { require(target != actor); s.hidden[actor] = s.hidden[actor].orEmpty() + "\n第${s.round}夜查验 $target：${if (s.roles[target] == "狼人") "狼人" else "好人"}" }
                s.actions[actor] = target
                if (s.actions.keys.containsAll(s.alive)) {
                    val attacks = s.actions.filterKeys { s.roles[it] == "狼人" }.values.filter { it.isNotEmpty() }.groupingBy { it }.eachCount()
                    val max = attacks.values.maxOrNull()
                    val leaders = attacks.filterValues { it == max }.keys
                    val killed = leaders.singleOrNull()
                    val guarded = s.actions.filterKeys { s.roles[it] == "守卫" }.values
                    if (killed != null && killed !in guarded) { s.alive.remove(killed); s.events.add("第${s.round}天：$killed 出局") }
                    else s.events.add("第${s.round}天：平安夜")
                    s.actions.clear(); s.phase = "discussion"; winWolf(s)
                }
            }
            "discussion" -> {
                s.actions[actor] = text.take(1000)
                s.events.add("$actor：${text.ifBlank { "结束本次讨论发言" }.take(1000)}")
                if (s.actions.keys.containsAll(s.alive)) { s.actions.clear(); s.phase = "vote"; s.events.add("讨论完成，请投票；可弃票，平票无人出局。") }
            }
            "vote" -> {
                require(target != actor) { "不能投自己" }; s.actions[actor] = target
                if (s.actions.keys.containsAll(s.alive)) {
                    s.events.add("投票：${s.actions.entries.joinToString { "${it.key}→${it.value.ifBlank { "弃票" }}" }}")
                    val votes = s.actions.values.filter { it.isNotEmpty() }.groupingBy { it }.eachCount()
                    val max = votes.values.maxOrNull(); val victim = votes.filterValues { it == max }.keys.singleOrNull()
                    if (victim != null) { s.alive.remove(victim); s.events.add("$victim 被投票出局") } else s.events.add("本次无人出局")
                    s.actions.clear(); s.round++; s.phase = "night"; winWolf(s)
                }
            }
            else -> error("未知阶段")
        }
    }
    private fun winWolf(s: GroupGameState) {
        val wolves = s.alive.count { s.roles[it] == "狼人" }
        if (wolves == 0 || wolves >= s.alive.size-wolves) {
            s.winner = if (wolves == 0) "好人" else "狼人"; s.phase = "ended"; s.events.add("${s.winner}获胜")
        }
    }
    fun narrative(s: GroupGameState, actor: String, text: String, hidden: String) {
        check(s.kind in setOf("story", "custom") && s.phase == "playing" && s.players[s.turn] == actor) { "还没轮到你行动" }
        require(text.isNotBlank() || hidden.isNotBlank()) { "请填写行动" }
        require(text.length <= 2000 && hidden.length <= 2000)
        if (text.isNotBlank()) s.events.add("$actor：$text")
        if (hidden.isNotBlank()) s.hidden[actor] = (s.hidden[actor].orEmpty()+"\n"+hidden).takeLast(6000)
        s.turn = (s.turn+1)%s.players.size
        if (s.turn == 0) s.round++
    }
}
