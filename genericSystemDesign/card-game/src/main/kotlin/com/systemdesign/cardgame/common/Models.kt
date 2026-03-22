package com.systemdesign.cardgame.common

/**
 * Core domain models for Generic Card Game Engine.
 * 
 * Extensibility Points:
 * - New card types: Extend Card class or create custom implementations
 * - New game rules: Implement CardGameStrategy interface
 * - New deck types: Use DeckFactory to create custom decks
 * 
 * Breaking Changes Required For:
 * - Changing core card structure
 * - Modifying game phase transitions
 */

/** Standard playing card suits */
enum class Suit {
    HEARTS, DIAMONDS, CLUBS, SPADES;
    
    fun symbol(): String = when (this) {
        HEARTS -> "♥"
        DIAMONDS -> "♦"
        CLUBS -> "♣"
        SPADES -> "♠"
    }
    
    fun color(): CardColor = when (this) {
        HEARTS, DIAMONDS -> CardColor.RED
        CLUBS, SPADES -> CardColor.BLACK
    }
}

/** Card colors */
enum class CardColor { RED, BLACK }

/** Standard playing card ranks */
enum class Rank(val symbol: String) {
    ACE("A"),
    TWO("2"),
    THREE("3"),
    FOUR("4"),
    FIVE("5"),
    SIX("6"),
    SEVEN("7"),
    EIGHT("8"),
    NINE("9"),
    TEN("10"),
    JACK("J"),
    QUEEN("Q"),
    KING("K");
    
    fun defaultValue(): Int = ordinal + 1
    
    fun blackjackValue(): Int = when (this) {
        ACE -> 11
        TWO -> 2
        THREE -> 3
        FOUR -> 4
        FIVE -> 5
        SIX -> 6
        SEVEN -> 7
        EIGHT -> 8
        NINE -> 9
        TEN, JACK, QUEEN, KING -> 10
    }
}

/** A playing card with suit, rank, and computed value */
data class Card(
    val suit: Suit,
    val rank: Rank,
    val value: Int = rank.ordinal + 1
) {
    val isFaceCard: Boolean
        get() = rank in listOf(Rank.JACK, Rank.QUEEN, Rank.KING)
    
    override fun toString(): String = "${rank.symbol}${suit.symbol()}"
}

/** UNO card colors */
enum class UnoColor {
    RED, BLUE, GREEN, YELLOW, WILD
}

/** UNO card types */
enum class UnoCardType {
    NUMBER,
    SKIP,
    REVERSE,
    DRAW_TWO,
    WILD,
    WILD_DRAW_FOUR
}

/** UNO card representation */
data class UnoCard(
    val color: UnoColor,
    val type: UnoCardType,
    val number: Int? = null
) {
    override fun toString(): String = when (type) {
        UnoCardType.NUMBER -> "$color $number"
        UnoCardType.WILD, UnoCardType.WILD_DRAW_FOUR -> type.name
        else -> "$color $type"
    }
}

/** A deck of cards */
data class Deck<T>(
    val cards: MutableList<T>
) {
    val size: Int get() = cards.size
    val isEmpty: Boolean get() = cards.isEmpty()
    
    fun shuffle() {
        cards.shuffle()
    }
    
    fun draw(): T? = if (cards.isNotEmpty()) cards.removeAt(0) else null
    
    fun drawMultiple(count: Int): List<T> {
        val drawn = mutableListOf<T>()
        repeat(count) {
            draw()?.let { drawn.add(it) }
        }
        return drawn
    }
    
    fun peek(): T? = cards.firstOrNull()
    
    fun addToTop(card: T) {
        cards.add(0, card)
    }
    
    fun addToBottom(card: T) {
        cards.add(card)
    }
    
    fun reset(newCards: List<T>) {
        cards.clear()
        cards.addAll(newCards)
    }
}

/** A player's hand of cards */
data class Hand<T>(
    val playerId: String,
    val cards: MutableList<T> = mutableListOf()
) {
    val size: Int get() = cards.size
    val isEmpty: Boolean get() = cards.isEmpty()
    
    fun addCard(card: T) {
        cards.add(card)
    }
    
    fun addCards(newCards: List<T>) {
        cards.addAll(newCards)
    }
    
    fun removeCard(card: T): Boolean = cards.remove(card)
    
    fun removeCardAt(index: Int): T? = 
        if (index in cards.indices) cards.removeAt(index) else null
    
    fun clear(): List<T> {
        val removed = cards.toList()
        cards.clear()
        return removed
    }
    
    fun contains(card: T): Boolean = cards.contains(card)
}

/** Player in a card game */
data class Player(
    val id: String,
    val name: String,
    var chips: Int = 0,
    var isActive: Boolean = true,
    var hasFolded: Boolean = false
) {
    fun bet(amount: Int): Boolean {
        if (amount > chips) return false
        chips -= amount
        return true
    }
    
    fun win(amount: Int) {
        chips += amount
    }
    
    fun fold() {
        hasFolded = true
    }
    
    fun reset() {
        hasFolded = false
    }
}

/** Game phases as a sealed class hierarchy */
sealed class GamePhase {
    data object Setup : GamePhase()
    data object Dealing : GamePhase()
    data object Playing : GamePhase()
    data object Showdown : GamePhase()
    data object GameOver : GamePhase()
    
    data class BettingRound(val roundNumber: Int) : GamePhase()
    data class PlayerTurn(val playerId: String) : GamePhase()
}

/** Result of a game action */
sealed class GameAction {
    data class Deal(val playerId: String, val cardCount: Int) : GameAction()
    data class Play(val playerId: String, val cardIndex: Int) : GameAction()
    data class Draw(val playerId: String, val count: Int = 1) : GameAction()
    data class Bet(val playerId: String, val amount: Int) : GameAction()
    data class Fold(val playerId: String) : GameAction()
    data class Call(val playerId: String) : GameAction()
    data class Raise(val playerId: String, val amount: Int) : GameAction()
    data class Check(val playerId: String) : GameAction()
    data object NextPhase : GameAction()
}

/** Result of executing a game action */
sealed class ActionResult {
    data class Success(val message: String = "") : ActionResult()
    data class InvalidAction(val reason: String) : ActionResult()
    data class InvalidPhase(val currentPhase: GamePhase, val requiredPhase: GamePhase) : ActionResult()
    data class PlayerNotFound(val playerId: String) : ActionResult()
    data class InsufficientChips(val required: Int, val available: Int) : ActionResult()
    data class GameEnded(val winnerId: String?, val winnings: Int = 0) : ActionResult()
}

/** Poker hand rankings */
enum class PokerHandRank(val value: Int, val displayName: String) {
    HIGH_CARD(1, "High Card"),
    ONE_PAIR(2, "One Pair"),
    TWO_PAIR(3, "Two Pair"),
    THREE_OF_A_KIND(4, "Three of a Kind"),
    STRAIGHT(5, "Straight"),
    FLUSH(6, "Flush"),
    FULL_HOUSE(7, "Full House"),
    FOUR_OF_A_KIND(8, "Four of a Kind"),
    STRAIGHT_FLUSH(9, "Straight Flush"),
    ROYAL_FLUSH(10, "Royal Flush")
}

/** Evaluated poker hand */
data class PokerHand(
    val rank: PokerHandRank,
    val highCards: List<Rank>,
    val description: String
) : Comparable<PokerHand> {
    override fun compareTo(other: PokerHand): Int {
        val rankCompare = rank.value.compareTo(other.rank.value)
        if (rankCompare != 0) return rankCompare
        
        highCards.zip(other.highCards).forEach { (thisCard, otherCard) ->
            val cardCompare = thisCard.ordinal.compareTo(otherCard.ordinal)
            if (cardCompare != 0) return cardCompare
        }
        return 0
    }
}

/** Game configuration */
data class GameConfig(
    val minPlayers: Int = 2,
    val maxPlayers: Int = 8,
    val startingChips: Int = 1000,
    val smallBlind: Int = 10,
    val bigBlind: Int = 20,
    val cardsPerPlayer: Int = 2,
    val deckType: DeckType = DeckType.STANDARD_52
)

/** Types of decks available */
enum class DeckType {
    STANDARD_52,
    STANDARD_54_WITH_JOKERS,
    UNO,
    CUSTOM
}

/** Game event for observers */
sealed class GameEvent {
    data class PhaseChanged(val from: GamePhase, val to: GamePhase) : GameEvent()
    data class CardDealt(val playerId: String, val cardCount: Int) : GameEvent()
    data class CardPlayed(val playerId: String, val card: Any) : GameEvent()
    data class BetPlaced(val playerId: String, val amount: Int) : GameEvent()
    data class PlayerFolded(val playerId: String) : GameEvent()
    data class PlayerEliminated(val playerId: String) : GameEvent()
    data class PotUpdated(val newTotal: Int) : GameEvent()
    data class WinnerDeclared(val playerId: String, val winnings: Int, val handDescription: String?) : GameEvent()
    data class Error(val message: String) : GameEvent()
}

/** Observer interface for game events */
interface GameObserver {
    fun onGameEvent(event: GameEvent)
}
