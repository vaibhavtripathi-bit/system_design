package com.systemdesign.cardgame.approach_03_factory_deck

import com.systemdesign.cardgame.common.*
import kotlin.random.Random

/**
 * Approach 3: Factory Pattern for Deck Creation
 * 
 * Different card games use different decks: standard 52-card, UNO, custom themed decks.
 * The Factory pattern provides a clean way to create various deck types without exposing
 * the creation logic to the client code.
 * 
 * Pattern: Factory Method + Abstract Factory
 * 
 * Trade-offs:
 * + Encapsulates deck creation complexity
 * + Easy to add new deck types without changing existing code
 * + Decks can be configured with different parameters
 * - May introduce many factory classes for simple cases
 * - Client must know which factory to use
 * 
 * When to use:
 * - When you need multiple deck types
 * - When deck creation involves complex logic
 * - When you want to defer deck type selection to runtime
 * 
 * Extensibility:
 * - New deck type: Implement DeckFactory interface
 * - Custom deck: Use CustomDeckBuilder
 * - New shuffling algorithm: Implement ShuffleStrategy
 */

/** Factory interface for creating decks */
interface DeckFactory<T> {
    val deckType: DeckType
    val deckSize: Int
    
    fun createDeck(): Deck<T>
    fun createShuffledDeck(): Deck<T> {
        val deck = createDeck()
        deck.shuffle()
        return deck
    }
}

/** Factory for standard 52-card deck */
class Standard52DeckFactory : DeckFactory<Card> {
    override val deckType = DeckType.STANDARD_52
    override val deckSize = 52
    
    override fun createDeck(): Deck<Card> {
        val cards = mutableListOf<Card>()
        
        Suit.entries.forEach { suit ->
            Rank.entries.forEach { rank ->
                cards.add(Card(suit, rank, calculateValue(rank)))
            }
        }
        
        return Deck(cards)
    }
    
    private fun calculateValue(rank: Rank): Int = when (rank) {
        Rank.ACE -> 14
        Rank.KING -> 13
        Rank.QUEEN -> 12
        Rank.JACK -> 11
        else -> rank.ordinal + 1
    }
}

/** Joker card for decks that include them */
data class JokerCard(val color: CardColor) {
    override fun toString(): String = "Joker ($color)"
}

/** Card that can be either standard or joker */
sealed class PlayingCard {
    data class Standard(val card: Card) : PlayingCard() {
        override fun toString(): String = card.toString()
    }
    data class Joker(val color: CardColor) : PlayingCard() {
        override fun toString(): String = "Joker ($color)"
    }
}

/** Factory for 54-card deck with jokers */
class Standard54DeckFactory : DeckFactory<PlayingCard> {
    override val deckType = DeckType.STANDARD_54_WITH_JOKERS
    override val deckSize = 54
    
    override fun createDeck(): Deck<PlayingCard> {
        val cards = mutableListOf<PlayingCard>()
        
        Suit.entries.forEach { suit ->
            Rank.entries.forEach { rank ->
                cards.add(PlayingCard.Standard(Card(suit, rank)))
            }
        }
        
        cards.add(PlayingCard.Joker(CardColor.RED))
        cards.add(PlayingCard.Joker(CardColor.BLACK))
        
        return Deck(cards)
    }
}

/** Factory for UNO deck */
class UnoDeckFactory : DeckFactory<UnoCard> {
    override val deckType = DeckType.UNO
    override val deckSize = 108
    
    override fun createDeck(): Deck<UnoCard> {
        val cards = mutableListOf<UnoCard>()
        
        val colors = listOf(UnoColor.RED, UnoColor.BLUE, UnoColor.GREEN, UnoColor.YELLOW)
        
        colors.forEach { color ->
            cards.add(UnoCard(color, UnoCardType.NUMBER, 0))
            
            for (num in 1..9) {
                cards.add(UnoCard(color, UnoCardType.NUMBER, num))
                cards.add(UnoCard(color, UnoCardType.NUMBER, num))
            }
            
            repeat(2) {
                cards.add(UnoCard(color, UnoCardType.SKIP))
                cards.add(UnoCard(color, UnoCardType.REVERSE))
                cards.add(UnoCard(color, UnoCardType.DRAW_TWO))
            }
        }
        
        repeat(4) {
            cards.add(UnoCard(UnoColor.WILD, UnoCardType.WILD))
            cards.add(UnoCard(UnoColor.WILD, UnoCardType.WILD_DRAW_FOUR))
        }
        
        return Deck(cards)
    }
}

/** Configuration for custom deck creation */
data class CustomDeckConfig(
    val suits: List<Suit> = Suit.entries,
    val ranks: List<Rank> = Rank.entries,
    val includeJokers: Int = 0,
    val copies: Int = 1,
    val valueCalculator: (Rank) -> Int = { it.ordinal + 1 }
)

/** Factory for custom decks */
class CustomDeckFactory(
    private val config: CustomDeckConfig = CustomDeckConfig()
) : DeckFactory<Card> {
    override val deckType = DeckType.CUSTOM
    override val deckSize: Int
        get() = config.suits.size * config.ranks.size * config.copies
    
    override fun createDeck(): Deck<Card> {
        val cards = mutableListOf<Card>()
        
        repeat(config.copies) {
            config.suits.forEach { suit ->
                config.ranks.forEach { rank ->
                    cards.add(Card(suit, rank, config.valueCalculator(rank)))
                }
            }
        }
        
        return Deck(cards)
    }
}

/** Shuffling strategies */
interface ShuffleStrategy<T> {
    fun shuffle(cards: MutableList<T>)
}

/** Standard Fisher-Yates shuffle */
class FisherYatesShuffle<T> : ShuffleStrategy<T> {
    override fun shuffle(cards: MutableList<T>) {
        for (i in cards.size - 1 downTo 1) {
            val j = Random.nextInt(i + 1)
            val temp = cards[i]
            cards[i] = cards[j]
            cards[j] = temp
        }
    }
}

/** Riffle shuffle (simulates human shuffle) */
class RiffleShuffle<T>(private val iterations: Int = 7) : ShuffleStrategy<T> {
    override fun shuffle(cards: MutableList<T>) {
        repeat(iterations) {
            val mid = cards.size / 2
            val left = cards.subList(0, mid).toMutableList()
            val right = cards.subList(mid, cards.size).toMutableList()
            
            cards.clear()
            while (left.isNotEmpty() || right.isNotEmpty()) {
                if (left.isNotEmpty() && (right.isEmpty() || Random.nextBoolean())) {
                    cards.add(left.removeAt(0))
                }
                if (right.isNotEmpty() && (left.isEmpty() || Random.nextBoolean())) {
                    cards.add(right.removeAt(0))
                }
            }
        }
    }
}

/** Overhand shuffle (simulates casual shuffle) */
class OverhandShuffle<T>(private val iterations: Int = 10) : ShuffleStrategy<T> {
    override fun shuffle(cards: MutableList<T>) {
        repeat(iterations) {
            val result = mutableListOf<T>()
            while (cards.isNotEmpty()) {
                val chunkSize = Random.nextInt(1, minOf(5, cards.size + 1))
                val chunk = cards.take(chunkSize)
                cards.subList(0, chunkSize).clear()
                result.addAll(0, chunk)
            }
            cards.addAll(result)
        }
    }
}

/** No shuffling (for testing) */
class NoShuffle<T> : ShuffleStrategy<T> {
    override fun shuffle(cards: MutableList<T>) {
    }
}

/** Builder for creating decks with custom configuration */
class DeckBuilder<T> {
    private var cards = mutableListOf<T>()
    private var shuffleStrategy: ShuffleStrategy<T> = FisherYatesShuffle()
    
    fun addCard(card: T): DeckBuilder<T> {
        cards.add(card)
        return this
    }
    
    fun addCards(newCards: List<T>): DeckBuilder<T> {
        cards.addAll(newCards)
        return this
    }
    
    fun withShuffleStrategy(strategy: ShuffleStrategy<T>): DeckBuilder<T> {
        shuffleStrategy = strategy
        return this
    }
    
    fun shuffle(): DeckBuilder<T> {
        shuffleStrategy.shuffle(cards)
        return this
    }
    
    fun build(): Deck<T> = Deck(cards.toMutableList())
    
    fun buildShuffled(): Deck<T> {
        shuffle()
        return build()
    }
}

/** Registry for deck factories */
object DeckFactoryRegistry {
    private val factories = mutableMapOf<DeckType, DeckFactory<*>>()
    
    init {
        register(Standard52DeckFactory())
        register(Standard54DeckFactory())
        register(UnoDeckFactory())
    }
    
    fun <T> register(factory: DeckFactory<T>) {
        factories[factory.deckType] = factory
    }
    
    @Suppress("UNCHECKED_CAST")
    fun <T> getFactory(type: DeckType): DeckFactory<T>? {
        return factories[type] as? DeckFactory<T>
    }
    
    fun getAvailableTypes(): Set<DeckType> = factories.keys.toSet()
}

/** Game engine using factory pattern for deck creation */
class FactoryBasedCardGame<T>(
    private val deckFactory: DeckFactory<T>,
    private val shuffleStrategy: ShuffleStrategy<T> = FisherYatesShuffle(),
    private val config: GameConfig = GameConfig()
) {
    private val players = mutableListOf<Player>()
    private var deck = deckFactory.createDeck()
    private val hands = mutableMapOf<String, Hand<T>>()
    private val discardPile = mutableListOf<T>()
    private val observers = mutableListOf<GameObserver>()
    
    fun getPlayers(): List<Player> = players.toList()
    fun getDeck(): Deck<T> = deck
    fun getHand(playerId: String): Hand<T>? = hands[playerId]
    fun getDiscardPile(): List<T> = discardPile.toList()
    
    fun addPlayer(player: Player): Boolean {
        if (players.size >= config.maxPlayers) return false
        if (players.any { it.id == player.id }) return false
        
        players.add(player.copy(chips = config.startingChips))
        return true
    }
    
    fun initializeDeck() {
        deck = deckFactory.createDeck()
        shuffleStrategy.shuffle(deck.cards)
    }
    
    fun shuffleDeck() {
        shuffleStrategy.shuffle(deck.cards)
    }
    
    fun dealCards(cardsPerPlayer: Int): Map<String, Hand<T>> {
        hands.clear()
        
        repeat(cardsPerPlayer) {
            players.filter { it.isActive }.forEach { player ->
                val hand = hands.getOrPut(player.id) { Hand(player.id) }
                deck.draw()?.let { hand.addCard(it) }
            }
        }
        
        players.forEach { player ->
            hands[player.id]?.let { hand ->
                notifyEvent(GameEvent.CardDealt(player.id, hand.size))
            }
        }
        
        return hands.toMap()
    }
    
    fun dealToPlayer(playerId: String, count: Int): List<T> {
        val hand = hands.getOrPut(playerId) { Hand(playerId) }
        val dealt = mutableListOf<T>()
        
        repeat(count) {
            deck.draw()?.let { card ->
                hand.addCard(card)
                dealt.add(card)
            }
        }
        
        if (dealt.isNotEmpty()) {
            notifyEvent(GameEvent.CardDealt(playerId, dealt.size))
        }
        
        return dealt
    }
    
    fun playerDraws(playerId: String, count: Int = 1): List<T> {
        if (deck.size < count) {
            reshuffleDiscardPile()
        }
        
        return dealToPlayer(playerId, count)
    }
    
    fun playerDiscards(playerId: String, cardIndex: Int): T? {
        val hand = hands[playerId] ?: return null
        val card = hand.removeCardAt(cardIndex) ?: return null
        
        discardPile.add(card)
        notifyEvent(GameEvent.CardPlayed(playerId, card as Any))
        
        return card
    }
    
    private fun reshuffleDiscardPile() {
        val topCard = discardPile.removeLastOrNull()
        deck.reset(discardPile)
        shuffleStrategy.shuffle(deck.cards)
        discardPile.clear()
        topCard?.let { discardPile.add(it) }
    }
    
    fun resetGame() {
        hands.clear()
        discardPile.clear()
        players.forEach { 
            it.isActive = true
            it.hasFolded = false
        }
        initializeDeck()
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

/** Specialized blackjack game using factory pattern */
class BlackjackGame(
    deckCount: Int = 6
) {
    private val multiDeckConfig = CustomDeckConfig(copies = deckCount)
    private val deckFactory = CustomDeckFactory(multiDeckConfig)
    private val game = FactoryBasedCardGame<Card>(deckFactory)
    
    private var dealerHand = Hand<Card>("dealer")
    
    fun addPlayer(name: String): Boolean {
        return game.addPlayer(Player(name, name))
    }
    
    fun startRound() {
        if (game.getDeck().size < 20) {
            game.initializeDeck()
        }
        
        dealerHand = Hand("dealer")
        
        game.dealCards(2)
        
        repeat(2) {
            game.getDeck().draw()?.let { dealerHand.addCard(it) }
        }
    }
    
    fun getPlayerHand(playerId: String): Hand<Card>? = game.getHand(playerId)
    fun getDealerHand(): Hand<Card> = dealerHand
    fun getDealerUpCard(): Card? = dealerHand.cards.firstOrNull()
    
    fun hit(playerId: String): Card? {
        val drawn = game.playerDraws(playerId, 1)
        return drawn.firstOrNull()
    }
    
    @Suppress("UNUSED_PARAMETER")
    fun stand(playerId: String) {
    }
    
    fun dealerPlay(): List<Card> {
        val drawn = mutableListOf<Card>()
        while (calculateHandValue(dealerHand) < 17) {
            game.getDeck().draw()?.let { card ->
                dealerHand.addCard(card)
                drawn.add(card)
            }
        }
        return drawn
    }
    
    fun calculateHandValue(hand: Hand<Card>): Int {
        var value = 0
        var aces = 0
        
        for (card in hand.cards) {
            when (card.rank) {
                Rank.ACE -> {
                    aces++
                    value += 11
                }
                Rank.JACK, Rank.QUEEN, Rank.KING -> value += 10
                else -> value += card.rank.ordinal + 1
            }
        }
        
        while (value > 21 && aces > 0) {
            value -= 10
            aces--
        }
        
        return value
    }
    
    fun isBust(hand: Hand<Card>): Boolean = calculateHandValue(hand) > 21
    
    fun isBlackjack(hand: Hand<Card>): Boolean = 
        hand.cards.size == 2 && calculateHandValue(hand) == 21
    
    data class RoundResult(
        val playerId: String,
        val playerValue: Int,
        val dealerValue: Int,
        val outcome: Outcome
    )
    
    enum class Outcome { WIN, LOSE, PUSH, BLACKJACK }
    
    fun determineOutcome(playerId: String): RoundResult? {
        val playerHand = game.getHand(playerId) ?: return null
        val playerValue = calculateHandValue(playerHand)
        val dealerValue = calculateHandValue(dealerHand)
        
        val outcome = when {
            isBust(playerHand) -> Outcome.LOSE
            isBust(dealerHand) -> Outcome.WIN
            isBlackjack(playerHand) && !isBlackjack(dealerHand) -> Outcome.BLACKJACK
            isBlackjack(dealerHand) && !isBlackjack(playerHand) -> Outcome.LOSE
            playerValue > dealerValue -> Outcome.WIN
            playerValue < dealerValue -> Outcome.LOSE
            else -> Outcome.PUSH
        }
        
        return RoundResult(playerId, playerValue, dealerValue, outcome)
    }
}

/** Helper functions for common deck operations */
object DeckUtils {
    fun createStandardDeck(): Deck<Card> = Standard52DeckFactory().createDeck()
    
    fun createShuffledStandardDeck(): Deck<Card> = Standard52DeckFactory().createShuffledDeck()
    
    fun createUnoDeck(): Deck<UnoCard> = UnoDeckFactory().createDeck()
    
    fun createMultiDeck(count: Int): Deck<Card> {
        val config = CustomDeckConfig(copies = count)
        return CustomDeckFactory(config).createDeck()
    }
    
    fun <T> shuffleWithStrategy(deck: Deck<T>, strategy: ShuffleStrategy<T>) {
        strategy.shuffle(deck.cards)
    }
}
