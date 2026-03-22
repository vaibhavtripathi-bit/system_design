package com.systemdesign.cardgame.approach_01_strategy_rules

import com.systemdesign.cardgame.common.*

/**
 * Approach 1: Strategy Pattern for Game Rules
 * 
 * Different card games have different rules for dealing, playing, scoring, and winning.
 * The Strategy pattern allows swapping game rules at runtime without changing the game engine.
 * 
 * Pattern: Strategy
 * 
 * Trade-offs:
 * + Easy to add new game types without modifying existing code
 * + Game rules are encapsulated and testable in isolation
 * + Runtime flexibility to change game rules
 * - Each strategy must implement all interface methods
 * - Some games may have rules that don't fit the interface well
 * 
 * When to use:
 * - When you need to support multiple game types
 * - When game rules may change or be configurable
 * - When you want to test game rules independently
 * 
 * Extensibility:
 * - New game type: Implement CardGameStrategy interface
 * - New scoring method: Override calculateScore in strategy
 * - New hand evaluation: Override evaluateHand method
 */

/** Strategy interface for card game rules */
interface CardGameStrategy {
    val gameName: String
    val cardsPerPlayer: Int
    
    fun dealCards(deck: Deck<Card>, players: List<Player>): Map<String, Hand<Card>>
    fun isValidPlay(hand: Hand<Card>, cardIndex: Int, gameState: GameState): Boolean
    fun calculateScore(hand: Hand<Card>): Int
    fun evaluateHand(hand: Hand<Card>): HandEvaluation
    fun determineWinner(hands: Map<String, Hand<Card>>): WinnerResult
    fun isGameOver(gameState: GameState): Boolean
}

/** Current state of the game */
data class GameState(
    val currentPlayerId: String,
    val discardPile: MutableList<Card> = mutableListOf(),
    val communityCards: MutableList<Card> = mutableListOf(),
    val currentBet: Int = 0,
    val pot: Int = 0,
    val phase: GamePhase = GamePhase.Setup,
    val roundNumber: Int = 1
)

/** Result of hand evaluation */
data class HandEvaluation(
    val score: Int,
    val rankName: String,
    val description: String,
    val highCards: List<Card>
)

/** Result of winner determination */
sealed class WinnerResult {
    data class SingleWinner(val playerId: String, val evaluation: HandEvaluation) : WinnerResult()
    data class MultipleWinners(val playerIds: List<String>, val evaluation: HandEvaluation) : WinnerResult()
    data object NoWinner : WinnerResult()
}

/** Poker rules implementation */
class PokerRules : CardGameStrategy {
    override val gameName = "Texas Hold'em Poker"
    override val cardsPerPlayer = 2
    
    override fun dealCards(deck: Deck<Card>, players: List<Player>): Map<String, Hand<Card>> {
        val hands = mutableMapOf<String, Hand<Card>>()
        
        players.filter { it.isActive }.forEach { player ->
            val hand = Hand<Card>(player.id)
            repeat(cardsPerPlayer) {
                deck.draw()?.let { hand.addCard(it) }
            }
            hands[player.id] = hand
        }
        
        return hands
    }
    
    override fun isValidPlay(hand: Hand<Card>, cardIndex: Int, gameState: GameState): Boolean {
        return cardIndex in hand.cards.indices
    }
    
    override fun calculateScore(hand: Hand<Card>): Int {
        return evaluateHand(hand).score
    }
    
    override fun evaluateHand(hand: Hand<Card>): HandEvaluation {
        val cards = hand.cards.sortedByDescending { it.rank.ordinal }
        
        if (cards.size < 5) {
            val score = cards.sumOf { it.rank.ordinal }
            return HandEvaluation(score, "Incomplete Hand", "Less than 5 cards", cards)
        }
        
        val isFlush = cards.map { it.suit }.distinct().size == 1
        val ranks = cards.map { it.rank.ordinal }.sorted()
        val isStraight = isConsecutive(ranks)
        
        val rankGroups = cards.groupBy { it.rank }
        val groupSizes = rankGroups.values.map { it.size }.sortedDescending()
        
        return when {
            isFlush && isStraight && cards.any { it.rank == Rank.ACE } && 
                cards.any { it.rank == Rank.KING } -> {
                HandEvaluation(1000, PokerHandRank.ROYAL_FLUSH.displayName, 
                    "Royal Flush", cards.take(5))
            }
            isFlush && isStraight -> {
                val highCard = cards.maxByOrNull { it.rank.ordinal }!!
                HandEvaluation(900 + highCard.rank.ordinal, PokerHandRank.STRAIGHT_FLUSH.displayName,
                    "Straight Flush to ${highCard.rank}", cards.take(5))
            }
            groupSizes[0] == 4 -> {
                val fourKind = rankGroups.entries.first { it.value.size == 4 }
                HandEvaluation(800 + fourKind.key.ordinal, PokerHandRank.FOUR_OF_A_KIND.displayName,
                    "Four ${fourKind.key}s", fourKind.value + cards.filter { it.rank != fourKind.key }.take(1))
            }
            groupSizes[0] == 3 && groupSizes[1] == 2 -> {
                val threeKind = rankGroups.entries.first { it.value.size == 3 }
                val pair = rankGroups.entries.first { it.value.size == 2 }
                HandEvaluation(700 + threeKind.key.ordinal * 10 + pair.key.ordinal,
                    PokerHandRank.FULL_HOUSE.displayName,
                    "${threeKind.key}s full of ${pair.key}s", threeKind.value + pair.value)
            }
            isFlush -> {
                HandEvaluation(600 + cards.maxOf { it.rank.ordinal }, PokerHandRank.FLUSH.displayName,
                    "Flush, ${cards[0].suit}", cards.take(5))
            }
            isStraight -> {
                val highCard = cards.maxByOrNull { it.rank.ordinal }!!
                HandEvaluation(500 + highCard.rank.ordinal, PokerHandRank.STRAIGHT.displayName,
                    "Straight to ${highCard.rank}", cards.take(5))
            }
            groupSizes[0] == 3 -> {
                val threeKind = rankGroups.entries.first { it.value.size == 3 }
                HandEvaluation(400 + threeKind.key.ordinal, PokerHandRank.THREE_OF_A_KIND.displayName,
                    "Three ${threeKind.key}s", threeKind.value + cards.filter { it.rank != threeKind.key }.take(2))
            }
            groupSizes[0] == 2 && groupSizes[1] == 2 -> {
                val pairs = rankGroups.entries.filter { it.value.size == 2 }
                    .sortedByDescending { it.key.ordinal }
                val kicker = cards.filter { it.rank != pairs[0].key && it.rank != pairs[1].key }.first()
                HandEvaluation(300 + pairs[0].key.ordinal * 10 + pairs[1].key.ordinal,
                    PokerHandRank.TWO_PAIR.displayName,
                    "${pairs[0].key}s and ${pairs[1].key}s", pairs[0].value + pairs[1].value + listOf(kicker))
            }
            groupSizes[0] == 2 -> {
                val pair = rankGroups.entries.first { it.value.size == 2 }
                val kickers = cards.filter { it.rank != pair.key }.take(3)
                HandEvaluation(200 + pair.key.ordinal, PokerHandRank.ONE_PAIR.displayName,
                    "Pair of ${pair.key}s", pair.value + kickers)
            }
            else -> {
                HandEvaluation(100 + cards.maxOf { it.rank.ordinal }, PokerHandRank.HIGH_CARD.displayName,
                    "High Card: ${cards[0].rank}", cards.take(5))
            }
        }
    }
    
    private fun isConsecutive(ranks: List<Int>): Boolean {
        if (ranks.size < 5) return false
        val sorted = ranks.distinct().sorted()
        if (sorted.size < 5) return false
        
        for (i in 0 until sorted.size - 4) {
            if (sorted[i + 4] - sorted[i] == 4) return true
        }
        
        if (sorted.contains(12) && sorted.containsAll(listOf(0, 1, 2, 3))) return true
        return false
    }
    
    override fun determineWinner(hands: Map<String, Hand<Card>>): WinnerResult {
        if (hands.isEmpty()) return WinnerResult.NoWinner
        
        val evaluations = hands.mapValues { evaluateHand(it.value) }
        val maxScore = evaluations.values.maxOf { it.score }
        val winners = evaluations.filter { it.value.score == maxScore }
        
        return when {
            winners.size == 1 -> {
                val (playerId, eval) = winners.entries.first()
                WinnerResult.SingleWinner(playerId, eval)
            }
            winners.isNotEmpty() -> {
                WinnerResult.MultipleWinners(winners.keys.toList(), winners.values.first())
            }
            else -> WinnerResult.NoWinner
        }
    }
    
    override fun isGameOver(gameState: GameState): Boolean {
        return gameState.phase is GamePhase.GameOver
    }
}

/** Blackjack rules implementation */
class BlackjackRules : CardGameStrategy {
    override val gameName = "Blackjack"
    override val cardsPerPlayer = 2
    
    private val targetScore = 21
    private val dealerStandScore = 17
    
    override fun dealCards(deck: Deck<Card>, players: List<Player>): Map<String, Hand<Card>> {
        val hands = mutableMapOf<String, Hand<Card>>()
        
        repeat(cardsPerPlayer) {
            players.filter { it.isActive }.forEach { player ->
                val hand = hands.getOrPut(player.id) { Hand(player.id) }
                deck.draw()?.let { hand.addCard(it) }
            }
        }
        
        return hands
    }
    
    override fun isValidPlay(hand: Hand<Card>, cardIndex: Int, gameState: GameState): Boolean {
        return calculateScore(hand) < targetScore
    }
    
    override fun calculateScore(hand: Hand<Card>): Int {
        var score = 0
        var aces = 0
        
        for (card in hand.cards) {
            when (card.rank) {
                Rank.ACE -> {
                    aces++
                    score += 11
                }
                Rank.JACK, Rank.QUEEN, Rank.KING -> score += 10
                else -> score += card.rank.blackjackValue()
            }
        }
        
        while (score > targetScore && aces > 0) {
            score -= 10
            aces--
        }
        
        return score
    }
    
    override fun evaluateHand(hand: Hand<Card>): HandEvaluation {
        val score = calculateScore(hand)
        val isBlackjack = hand.cards.size == 2 && score == 21
        val isBust = score > targetScore
        
        val rankName = when {
            isBlackjack -> "Blackjack"
            isBust -> "Bust"
            score == 21 -> "21"
            else -> "Score: $score"
        }
        
        return HandEvaluation(
            score = if (isBust) 0 else if (isBlackjack) 100 else score,
            rankName = rankName,
            description = "Total: $score${if (isBust) " (Bust)" else ""}",
            highCards = hand.cards.sortedByDescending { it.rank.blackjackValue() }
        )
    }
    
    override fun determineWinner(hands: Map<String, Hand<Card>>): WinnerResult {
        if (hands.isEmpty()) return WinnerResult.NoWinner
        
        val evaluations = hands.mapValues { evaluateHand(it.value) }
        val validHands = evaluations.filter { it.value.score > 0 }
        
        if (validHands.isEmpty()) return WinnerResult.NoWinner
        
        val maxScore = validHands.values.maxOf { it.score }
        val winners = validHands.filter { it.value.score == maxScore }
        
        return when {
            winners.size == 1 -> {
                val (playerId, eval) = winners.entries.first()
                WinnerResult.SingleWinner(playerId, eval)
            }
            winners.isNotEmpty() -> {
                WinnerResult.MultipleWinners(winners.keys.toList(), winners.values.first())
            }
            else -> WinnerResult.NoWinner
        }
    }
    
    override fun isGameOver(gameState: GameState): Boolean {
        return gameState.phase is GamePhase.GameOver
    }
    
    fun shouldDealerHit(dealerHand: Hand<Card>): Boolean {
        return calculateScore(dealerHand) < dealerStandScore
    }
    
    fun isBlackjack(hand: Hand<Card>): Boolean {
        return hand.cards.size == 2 && calculateScore(hand) == 21
    }
    
    fun isBust(hand: Hand<Card>): Boolean {
        return calculateScore(hand) > targetScore
    }
}

/** UNO rules implementation - using standard Card for simplified representation */
class UnoRules : CardGameStrategy {
    override val gameName = "UNO"
    override val cardsPerPlayer = 7
    
    override fun dealCards(deck: Deck<Card>, players: List<Player>): Map<String, Hand<Card>> {
        val hands = mutableMapOf<String, Hand<Card>>()
        
        players.filter { it.isActive }.forEach { player ->
            val hand = Hand<Card>(player.id)
            repeat(cardsPerPlayer) {
                deck.draw()?.let { hand.addCard(it) }
            }
            hands[player.id] = hand
        }
        
        return hands
    }
    
    override fun isValidPlay(hand: Hand<Card>, cardIndex: Int, gameState: GameState): Boolean {
        if (cardIndex !in hand.cards.indices) return false
        
        val topCard = gameState.discardPile.lastOrNull() ?: return true
        val playCard = hand.cards[cardIndex]
        
        return playCard.suit == topCard.suit || 
               playCard.rank == topCard.rank ||
               isWildCard(playCard)
    }
    
    private fun isWildCard(card: Card): Boolean {
        return card.rank == Rank.ACE || card.rank == Rank.KING
    }
    
    override fun calculateScore(hand: Hand<Card>): Int {
        return hand.cards.sumOf { card ->
            when {
                card.rank == Rank.ACE -> 50
                card.rank == Rank.KING -> 50
                card.isFaceCard -> 20
                else -> card.rank.ordinal + 1
            }
        }
    }
    
    override fun evaluateHand(hand: Hand<Card>): HandEvaluation {
        val score = calculateScore(hand)
        
        return HandEvaluation(
            score = -score,
            rankName = if (hand.isEmpty) "UNO OUT" else "${hand.size} cards left",
            description = "Points: $score",
            highCards = hand.cards.sortedByDescending { it.rank.ordinal }
        )
    }
    
    override fun determineWinner(hands: Map<String, Hand<Card>>): WinnerResult {
        if (hands.isEmpty()) return WinnerResult.NoWinner
        
        val emptyHands = hands.filter { it.value.isEmpty }
        if (emptyHands.isNotEmpty()) {
            val winnerId = emptyHands.keys.first()
            return WinnerResult.SingleWinner(
                winnerId,
                HandEvaluation(1000, "UNO OUT", "Empty hand", emptyList())
            )
        }
        
        val evaluations = hands.mapValues { evaluateHand(it.value) }
        val maxScore = evaluations.values.maxOf { it.score }
        val winners = evaluations.filter { it.value.score == maxScore }
        
        return when {
            winners.size == 1 -> {
                val (playerId, eval) = winners.entries.first()
                WinnerResult.SingleWinner(playerId, eval)
            }
            winners.isNotEmpty() -> {
                WinnerResult.MultipleWinners(winners.keys.toList(), winners.values.first())
            }
            else -> WinnerResult.NoWinner
        }
    }
    
    override fun isGameOver(gameState: GameState): Boolean {
        return gameState.phase is GamePhase.GameOver
    }
    
    fun getPlayDirection(currentDirection: Int, card: Card): Int {
        return if (card.rank == Rank.JACK) -currentDirection else currentDirection
    }
    
    fun getDrawCount(card: Card): Int {
        return when (card.rank) {
            Rank.TWO -> 2
            Rank.KING -> 4
            else -> 0
        }
    }
}

/** Game engine that uses a strategy for rules */
class StrategyBasedCardGame(
    private var strategy: CardGameStrategy,
    private val config: GameConfig = GameConfig()
) {
    private val players = mutableListOf<Player>()
    private var deck = Deck<Card>(mutableListOf())
    private val hands = mutableMapOf<String, Hand<Card>>()
    private var gameState = GameState(currentPlayerId = "")
    private val observers = mutableListOf<GameObserver>()
    
    fun getStrategy(): CardGameStrategy = strategy
    fun getPlayers(): List<Player> = players.toList()
    fun getHand(playerId: String): Hand<Card>? = hands[playerId]
    fun getGameState(): GameState = gameState
    
    fun setStrategy(newStrategy: CardGameStrategy) {
        strategy = newStrategy
    }
    
    fun addPlayer(player: Player): Boolean {
        if (players.size >= config.maxPlayers) return false
        if (players.any { it.id == player.id }) return false
        
        players.add(player.copy(chips = config.startingChips))
        return true
    }
    
    fun removePlayer(playerId: String): Boolean {
        return players.removeIf { it.id == playerId }
    }
    
    fun setDeck(newDeck: Deck<Card>) {
        deck = newDeck
    }
    
    fun startGame(): ActionResult {
        if (players.size < config.minPlayers) {
            return ActionResult.InvalidAction("Need at least ${config.minPlayers} players")
        }
        
        deck.shuffle()
        val dealtHands = strategy.dealCards(deck, players)
        hands.clear()
        hands.putAll(dealtHands)
        
        gameState = gameState.copy(
            currentPlayerId = players.first().id,
            phase = GamePhase.Playing
        )
        
        notifyEvent(GameEvent.PhaseChanged(GamePhase.Setup, GamePhase.Playing))
        players.forEach { player ->
            hands[player.id]?.let { hand ->
                notifyEvent(GameEvent.CardDealt(player.id, hand.size))
            }
        }
        
        return ActionResult.Success("Game started with ${strategy.gameName} rules")
    }
    
    fun playCard(playerId: String, cardIndex: Int): ActionResult {
        val hand = hands[playerId] ?: return ActionResult.PlayerNotFound(playerId)
        
        if (gameState.currentPlayerId != playerId) {
            return ActionResult.InvalidAction("Not your turn")
        }
        
        if (!strategy.isValidPlay(hand, cardIndex, gameState)) {
            return ActionResult.InvalidAction("Invalid play")
        }
        
        val card = hand.removeCardAt(cardIndex) ?: return ActionResult.InvalidAction("Card not found")
        gameState.discardPile.add(card)
        
        notifyEvent(GameEvent.CardPlayed(playerId, card))
        
        if (hand.isEmpty) {
            return checkForWinner()
        }
        
        advanceToNextPlayer()
        return ActionResult.Success("Played $card")
    }
    
    fun drawCard(playerId: String): ActionResult {
        if (deck.isEmpty) {
            reshuffleDeck()
        }
        
        val hand = hands[playerId] ?: return ActionResult.PlayerNotFound(playerId)
        val card = deck.draw() ?: return ActionResult.InvalidAction("No cards left")
        
        hand.addCard(card)
        return ActionResult.Success("Drew a card")
    }
    
    private fun reshuffleDeck() {
        val topCard = gameState.discardPile.removeLastOrNull()
        deck.reset(gameState.discardPile)
        deck.shuffle()
        gameState.discardPile.clear()
        topCard?.let { gameState.discardPile.add(it) }
    }
    
    private fun advanceToNextPlayer() {
        val activePlayers = players.filter { it.isActive && !it.hasFolded }
        val currentIndex = activePlayers.indexOfFirst { it.id == gameState.currentPlayerId }
        val nextIndex = (currentIndex + 1) % activePlayers.size
        gameState = gameState.copy(currentPlayerId = activePlayers[nextIndex].id)
    }
    
    fun checkForWinner(): ActionResult {
        val result = strategy.determineWinner(hands)
        
        return when (result) {
            is WinnerResult.SingleWinner -> {
                gameState = gameState.copy(phase = GamePhase.GameOver)
                notifyEvent(GameEvent.WinnerDeclared(result.playerId, gameState.pot, result.evaluation.description))
                ActionResult.GameEnded(result.playerId, gameState.pot)
            }
            is WinnerResult.MultipleWinners -> {
                gameState = gameState.copy(phase = GamePhase.GameOver)
                val winnerId = result.playerIds.first()
                notifyEvent(GameEvent.WinnerDeclared(winnerId, gameState.pot / result.playerIds.size, result.evaluation.description))
                ActionResult.GameEnded(winnerId, gameState.pot / result.playerIds.size)
            }
            WinnerResult.NoWinner -> {
                ActionResult.InvalidAction("No winner could be determined")
            }
        }
    }
    
    fun evaluateCurrentHands(): Map<String, HandEvaluation> {
        return hands.mapValues { strategy.evaluateHand(it.value) }
    }
    
    fun addObserver(observer: GameObserver) {
        observers.add(observer)
    }
    
    fun removeObserver(observer: GameObserver) {
        observers.remove(observer)
    }
    
    private fun notifyEvent(event: GameEvent) {
        observers.forEach { it.onGameEvent(event) }
    }
}
