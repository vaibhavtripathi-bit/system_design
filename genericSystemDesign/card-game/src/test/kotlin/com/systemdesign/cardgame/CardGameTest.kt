package com.systemdesign.cardgame

import com.systemdesign.cardgame.common.*
import com.systemdesign.cardgame.approach_01_strategy_rules.*
import com.systemdesign.cardgame.approach_02_state_machine.*
import com.systemdesign.cardgame.approach_03_factory_deck.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested

class CardGameTest {

    @Nested
    inner class DeckTests {
        
        @Test
        fun `standard 52-card deck has correct size`() {
            val factory = Standard52DeckFactory()
            val deck = factory.createDeck()
            
            assertEquals(52, deck.size)
            assertEquals(52, factory.deckSize)
        }
        
        @Test
        fun `standard deck contains all suits and ranks`() {
            val deck = Standard52DeckFactory().createDeck()
            
            Suit.entries.forEach { suit ->
                val suitCards = deck.cards.filter { it.suit == suit }
                assertEquals(13, suitCards.size, "Should have 13 cards of $suit")
            }
            
            Rank.entries.forEach { rank ->
                val rankCards = deck.cards.filter { it.rank == rank }
                assertEquals(4, rankCards.size, "Should have 4 cards of $rank")
            }
        }
        
        @Test
        fun `deck with jokers has 54 cards`() {
            val factory = Standard54DeckFactory()
            val deck = factory.createDeck()
            
            assertEquals(54, deck.size)
            
            val jokers = deck.cards.filterIsInstance<PlayingCard.Joker>()
            assertEquals(2, jokers.size)
        }
        
        @Test
        fun `UNO deck has correct size and composition`() {
            val factory = UnoDeckFactory()
            val deck = factory.createDeck()
            
            assertEquals(108, deck.size)
            
            val wildcards = deck.cards.filter { it.type == UnoCardType.WILD }
            assertEquals(4, wildcards.size)
            
            val wildDrawFours = deck.cards.filter { it.type == UnoCardType.WILD_DRAW_FOUR }
            assertEquals(4, wildDrawFours.size)
        }
        
        @Test
        fun `deck shuffle changes card order`() {
            val deck1 = Standard52DeckFactory().createDeck()
            val deck2 = Standard52DeckFactory().createDeck()
            
            val originalOrder = deck1.cards.toList()
            deck1.shuffle()
            
            assertNotEquals(originalOrder, deck1.cards, "Shuffled deck should differ from original")
            assertEquals(originalOrder, deck2.cards, "Unshuffled deck should retain original order")
        }
        
        @Test
        fun `draw removes card from deck`() {
            val deck = Standard52DeckFactory().createDeck()
            val initialSize = deck.size
            
            val card = deck.draw()
            
            assertNotNull(card)
            assertEquals(initialSize - 1, deck.size)
            assertFalse(deck.cards.contains(card))
        }
        
        @Test
        fun `draw from empty deck returns null`() {
            val deck = Deck<Card>(mutableListOf())
            
            val card = deck.draw()
            
            assertNull(card)
        }
        
        @Test
        fun `drawMultiple returns correct number of cards`() {
            val deck = Standard52DeckFactory().createDeck()
            
            val cards = deck.drawMultiple(5)
            
            assertEquals(5, cards.size)
            assertEquals(47, deck.size)
        }
        
        @Test
        fun `custom deck factory respects configuration`() {
            val config = CustomDeckConfig(
                suits = listOf(Suit.HEARTS, Suit.SPADES),
                ranks = listOf(Rank.ACE, Rank.KING, Rank.QUEEN),
                copies = 2
            )
            val factory = CustomDeckFactory(config)
            val deck = factory.createDeck()
            
            assertEquals(12, deck.size)
        }
    }

    @Nested
    inner class ShuffleStrategyTests {
        
        @Test
        fun `Fisher-Yates shuffle modifies deck`() {
            val cards = (1..10).toMutableList()
            val original = cards.toList()
            
            FisherYatesShuffle<Int>().shuffle(cards)
            
            assertEquals(original.size, cards.size)
            assertTrue(original.toSet() == cards.toSet())
        }
        
        @Test
        fun `Riffle shuffle modifies deck`() {
            val cards = (1..52).toMutableList()
            val original = cards.toList()
            
            RiffleShuffle<Int>(iterations = 3).shuffle(cards)
            
            assertEquals(original.size, cards.size)
            assertTrue(original.toSet() == cards.toSet())
        }
        
        @Test
        fun `NoShuffle keeps original order`() {
            val cards = (1..10).toMutableList()
            val original = cards.toList()
            
            NoShuffle<Int>().shuffle(cards)
            
            assertEquals(original, cards)
        }
        
        @Test
        fun `deck builder creates deck with shuffle strategy`() {
            val deck = DeckBuilder<Int>()
                .addCards((1..52).toList())
                .withShuffleStrategy(NoShuffle())
                .buildShuffled()
            
            assertEquals((1..52).toList(), deck.cards)
        }
    }

    @Nested
    inner class HandTests {
        
        @Test
        fun `hand starts empty`() {
            val hand = Hand<Card>("player1")
            
            assertTrue(hand.isEmpty)
            assertEquals(0, hand.size)
        }
        
        @Test
        fun `adding cards updates hand size`() {
            val hand = Hand<Card>("player1")
            val card = Card(Suit.HEARTS, Rank.ACE)
            
            hand.addCard(card)
            
            assertFalse(hand.isEmpty)
            assertEquals(1, hand.size)
            assertTrue(hand.contains(card))
        }
        
        @Test
        fun `removing card returns correct card`() {
            val hand = Hand<Card>("player1")
            val card1 = Card(Suit.HEARTS, Rank.ACE)
            val card2 = Card(Suit.SPADES, Rank.KING)
            hand.addCard(card1)
            hand.addCard(card2)
            
            val removed = hand.removeCardAt(0)
            
            assertEquals(card1, removed)
            assertEquals(1, hand.size)
            assertFalse(hand.contains(card1))
            assertTrue(hand.contains(card2))
        }
        
        @Test
        fun `clear hand returns all cards`() {
            val hand = Hand<Card>("player1")
            hand.addCard(Card(Suit.HEARTS, Rank.ACE))
            hand.addCard(Card(Suit.SPADES, Rank.KING))
            
            val cleared = hand.clear()
            
            assertEquals(2, cleared.size)
            assertTrue(hand.isEmpty)
        }
    }

    @Nested
    inner class PokerRulesTests {
        
        private val pokerRules = PokerRules()
        
        @Test
        fun `deals correct number of cards per player`() {
            val deck = Standard52DeckFactory().createDeck()
            val players = listOf(
                Player("p1", "Player 1"),
                Player("p2", "Player 2")
            )
            
            val hands = pokerRules.dealCards(deck, players)
            
            assertEquals(2, hands.size)
            hands.values.forEach { hand ->
                assertEquals(2, hand.size)
            }
        }
        
        @Test
        fun `evaluates high card correctly`() {
            val hand = Hand<Card>("test")
            hand.addCards(listOf(
                Card(Suit.HEARTS, Rank.ACE),
                Card(Suit.DIAMONDS, Rank.KING),
                Card(Suit.CLUBS, Rank.TEN),
                Card(Suit.SPADES, Rank.EIGHT),
                Card(Suit.HEARTS, Rank.THREE)
            ))
            
            val evaluation = pokerRules.evaluateHand(hand)
            
            assertEquals("High Card", evaluation.rankName)
        }
        
        @Test
        fun `evaluates pair correctly`() {
            val hand = Hand<Card>("test")
            hand.addCards(listOf(
                Card(Suit.HEARTS, Rank.ACE),
                Card(Suit.DIAMONDS, Rank.ACE),
                Card(Suit.CLUBS, Rank.TEN),
                Card(Suit.SPADES, Rank.EIGHT),
                Card(Suit.HEARTS, Rank.THREE)
            ))
            
            val evaluation = pokerRules.evaluateHand(hand)
            
            assertEquals("One Pair", evaluation.rankName)
        }
        
        @Test
        fun `evaluates two pair correctly`() {
            val hand = Hand<Card>("test")
            hand.addCards(listOf(
                Card(Suit.HEARTS, Rank.ACE),
                Card(Suit.DIAMONDS, Rank.ACE),
                Card(Suit.CLUBS, Rank.KING),
                Card(Suit.SPADES, Rank.KING),
                Card(Suit.HEARTS, Rank.THREE)
            ))
            
            val evaluation = pokerRules.evaluateHand(hand)
            
            assertEquals("Two Pair", evaluation.rankName)
        }
        
        @Test
        fun `evaluates three of a kind correctly`() {
            val hand = Hand<Card>("test")
            hand.addCards(listOf(
                Card(Suit.HEARTS, Rank.ACE),
                Card(Suit.DIAMONDS, Rank.ACE),
                Card(Suit.CLUBS, Rank.ACE),
                Card(Suit.SPADES, Rank.KING),
                Card(Suit.HEARTS, Rank.THREE)
            ))
            
            val evaluation = pokerRules.evaluateHand(hand)
            
            assertEquals("Three of a Kind", evaluation.rankName)
        }
        
        @Test
        fun `evaluates flush correctly`() {
            val hand = Hand<Card>("test")
            hand.addCards(listOf(
                Card(Suit.HEARTS, Rank.ACE),
                Card(Suit.HEARTS, Rank.KING),
                Card(Suit.HEARTS, Rank.TEN),
                Card(Suit.HEARTS, Rank.FIVE),
                Card(Suit.HEARTS, Rank.THREE)
            ))
            
            val evaluation = pokerRules.evaluateHand(hand)
            
            assertEquals("Flush", evaluation.rankName)
        }
        
        @Test
        fun `evaluates full house correctly`() {
            val hand = Hand<Card>("test")
            hand.addCards(listOf(
                Card(Suit.HEARTS, Rank.ACE),
                Card(Suit.DIAMONDS, Rank.ACE),
                Card(Suit.CLUBS, Rank.ACE),
                Card(Suit.SPADES, Rank.KING),
                Card(Suit.HEARTS, Rank.KING)
            ))
            
            val evaluation = pokerRules.evaluateHand(hand)
            
            assertEquals("Full House", evaluation.rankName)
        }
        
        @Test
        fun `evaluates four of a kind correctly`() {
            val hand = Hand<Card>("test")
            hand.addCards(listOf(
                Card(Suit.HEARTS, Rank.ACE),
                Card(Suit.DIAMONDS, Rank.ACE),
                Card(Suit.CLUBS, Rank.ACE),
                Card(Suit.SPADES, Rank.ACE),
                Card(Suit.HEARTS, Rank.KING)
            ))
            
            val evaluation = pokerRules.evaluateHand(hand)
            
            assertEquals("Four of a Kind", evaluation.rankName)
        }
        
        @Test
        fun `determines winner correctly`() {
            val hand1 = Hand<Card>("p1")
            hand1.addCards(listOf(
                Card(Suit.HEARTS, Rank.ACE),
                Card(Suit.DIAMONDS, Rank.ACE),
                Card(Suit.CLUBS, Rank.TEN),
                Card(Suit.SPADES, Rank.EIGHT),
                Card(Suit.HEARTS, Rank.THREE)
            ))
            
            val hand2 = Hand<Card>("p2")
            hand2.addCards(listOf(
                Card(Suit.HEARTS, Rank.KING),
                Card(Suit.DIAMONDS, Rank.QUEEN),
                Card(Suit.CLUBS, Rank.TEN),
                Card(Suit.SPADES, Rank.EIGHT),
                Card(Suit.HEARTS, Rank.THREE)
            ))
            
            val result = pokerRules.determineWinner(mapOf("p1" to hand1, "p2" to hand2))
            
            assertTrue(result is WinnerResult.SingleWinner)
            assertEquals("p1", (result as WinnerResult.SingleWinner).playerId)
        }
    }

    @Nested
    inner class BlackjackRulesTests {
        
        private val blackjackRules = BlackjackRules()
        
        @Test
        fun `calculates score with number cards`() {
            val hand = Hand<Card>("test")
            hand.addCards(listOf(
                Card(Suit.HEARTS, Rank.FIVE),
                Card(Suit.DIAMONDS, Rank.SEVEN)
            ))
            
            val score = blackjackRules.calculateScore(hand)
            
            assertEquals(12, score)
        }
        
        @Test
        fun `calculates score with face cards as 10`() {
            val hand = Hand<Card>("test")
            hand.addCards(listOf(
                Card(Suit.HEARTS, Rank.KING),
                Card(Suit.DIAMONDS, Rank.QUEEN)
            ))
            
            val score = blackjackRules.calculateScore(hand)
            
            assertEquals(20, score)
        }
        
        @Test
        fun `calculates ace as 11 when beneficial`() {
            val hand = Hand<Card>("test")
            hand.addCards(listOf(
                Card(Suit.HEARTS, Rank.ACE),
                Card(Suit.DIAMONDS, Rank.NINE)
            ))
            
            val score = blackjackRules.calculateScore(hand)
            
            assertEquals(20, score)
        }
        
        @Test
        fun `calculates ace as 1 when 11 would bust`() {
            val hand = Hand<Card>("test")
            hand.addCards(listOf(
                Card(Suit.HEARTS, Rank.ACE),
                Card(Suit.DIAMONDS, Rank.FIVE),
                Card(Suit.CLUBS, Rank.EIGHT)
            ))
            
            val score = blackjackRules.calculateScore(hand)
            
            assertEquals(14, score)
        }
        
        @Test
        fun `detects blackjack`() {
            val hand = Hand<Card>("test")
            hand.addCards(listOf(
                Card(Suit.HEARTS, Rank.ACE),
                Card(Suit.DIAMONDS, Rank.KING)
            ))
            
            assertTrue(blackjackRules.isBlackjack(hand))
            assertEquals(21, blackjackRules.calculateScore(hand))
        }
        
        @Test
        fun `detects bust`() {
            val hand = Hand<Card>("test")
            hand.addCards(listOf(
                Card(Suit.HEARTS, Rank.KING),
                Card(Suit.DIAMONDS, Rank.QUEEN),
                Card(Suit.CLUBS, Rank.FIVE)
            ))
            
            assertTrue(blackjackRules.isBust(hand))
        }
        
        @Test
        fun `dealer should hit below 17`() {
            val hand = Hand<Card>("dealer")
            hand.addCards(listOf(
                Card(Suit.HEARTS, Rank.TEN),
                Card(Suit.DIAMONDS, Rank.SIX)
            ))
            
            assertTrue(blackjackRules.shouldDealerHit(hand))
        }
        
        @Test
        fun `dealer should stand at 17 or above`() {
            val hand = Hand<Card>("dealer")
            hand.addCards(listOf(
                Card(Suit.HEARTS, Rank.TEN),
                Card(Suit.DIAMONDS, Rank.SEVEN)
            ))
            
            assertFalse(blackjackRules.shouldDealerHit(hand))
        }
    }

    @Nested
    inner class StateMachineTests {
        
        private lateinit var game: StateMachineCardGame
        
        @BeforeEach
        fun setup() {
            game = StateMachineCardGame(GameConfig(minPlayers = 2, maxPlayers = 6))
        }
        
        @Test
        fun `starts in waiting for players state`() {
            assertEquals(DetailedGameState.WAITING_FOR_PLAYERS, game.getState())
        }
        
        @Test
        fun `can add players in waiting state`() {
            val result = game.addPlayer(Player("p1", "Player 1"))
            
            assertTrue(result is ActionResult.Success)
            assertEquals(1, game.getPlayers().size)
        }
        
        @Test
        fun `cannot start game without enough players`() {
            game.addPlayer(Player("p1", "Player 1"))
            
            val result = game.startGame()
            
            assertTrue(result is ActionResult.InvalidAction)
        }
        
        @Test
        fun `transitions to dealing when game starts`() {
            game.addPlayer(Player("p1", "Player 1"))
            game.addPlayer(Player("p2", "Player 2"))
            
            game.startGame()
            
            assertEquals(DetailedGameState.PRE_FLOP_BETTING, game.getState())
        }
        
        @Test
        fun `deals hole cards to all players`() {
            game.addPlayer(Player("p1", "Player 1"))
            game.addPlayer(Player("p2", "Player 2"))
            game.startGame()
            
            val hand1 = game.getHand("p1")
            val hand2 = game.getHand("p2")
            
            assertNotNull(hand1)
            assertNotNull(hand2)
            assertEquals(2, hand1!!.size)
            assertEquals(2, hand2!!.size)
        }
        
        @Test
        fun `collects blinds at start of round`() {
            val config = GameConfig(minPlayers = 2, smallBlind = 10, bigBlind = 20, startingChips = 1000)
            game = StateMachineCardGame(config)
            
            game.addPlayer(Player("p1", "Player 1"))
            game.addPlayer(Player("p2", "Player 2"))
            game.startGame()
            
            val pot = game.getPot()
            assertEquals(30, pot)
        }
        
        @Test
        fun `rejects action when not player's turn`() {
            game.addPlayer(Player("p1", "Player 1"))
            game.addPlayer(Player("p2", "Player 2"))
            game.startGame()
            
            val currentPlayer = game.getCurrentPlayer()
            val otherPlayerId = if (currentPlayer?.id == "p1") "p2" else "p1"
            
            val result = game.performAction(otherPlayerId, BettingAction.CALL)
            
            assertTrue(result is ActionResult.InvalidAction)
        }
        
        @Test
        fun `fold eliminates player from round`() {
            game.addPlayer(Player("p1", "Player 1"))
            game.addPlayer(Player("p2", "Player 2"))
            game.startGame()
            
            val currentPlayer = game.getCurrentPlayer()!!
            game.performAction(currentPlayer.id, BettingAction.FOLD)
            
            val player = game.getPlayers().find { it.id == currentPlayer.id }
            assertTrue(player!!.hasFolded)
        }
        
        @Test
        fun `tracks state history`() {
            game.addPlayer(Player("p1", "Player 1"))
            game.addPlayer(Player("p2", "Player 2"))
            game.startGame()
            
            val history = game.getStateHistory()
            
            assertTrue(history.isNotEmpty())
            assertTrue(history.contains(DetailedGameState.WAITING_FOR_PLAYERS))
        }
    }

    @Nested
    inner class TurnBasedGameTests {
        
        private lateinit var game: TurnBasedStateMachineGame
        
        @BeforeEach
        fun setup() {
            game = TurnBasedStateMachineGame(GameConfig(minPlayers = 2, maxPlayers = 4))
        }
        
        @Test
        fun `starts in waiting for players state`() {
            assertEquals(TurnBasedStateMachineGame.TurnState.WAITING_FOR_PLAYERS, game.getState())
        }
        
        @Test
        fun `deals 7 cards to each player`() {
            game.addPlayer(Player("p1", "Player 1"))
            game.addPlayer(Player("p2", "Player 2"))
            game.startGame()
            
            assertEquals(7, game.getHand("p1")?.size)
            assertEquals(7, game.getHand("p2")?.size)
        }
        
        @Test
        fun `has starting discard card`() {
            game.addPlayer(Player("p1", "Player 1"))
            game.addPlayer(Player("p2", "Player 2"))
            game.startGame()
            
            assertNotNull(game.getTopDiscard())
        }
        
        @Test
        fun `rejects play when not your turn`() {
            game.addPlayer(Player("p1", "Player 1"))
            game.addPlayer(Player("p2", "Player 2"))
            game.startGame()
            
            val currentPlayer = game.getCurrentPlayer()!!
            val otherPlayer = if (currentPlayer.id == "p1") "p2" else "p1"
            
            val result = game.playCard(otherPlayer, 0)
            
            assertTrue(result is ActionResult.InvalidAction)
        }
        
        @Test
        fun `drawing card advances turn`() {
            game.addPlayer(Player("p1", "Player 1"))
            game.addPlayer(Player("p2", "Player 2"))
            game.startGame()
            
            val firstPlayer = game.getCurrentPlayer()!!
            game.drawCard(firstPlayer.id)
            
            assertNotEquals(firstPlayer.id, game.getCurrentPlayer()?.id)
        }
    }

    @Nested
    inner class StrategyBasedGameTests {
        
        @Test
        fun `can switch strategies at runtime`() {
            val game = StrategyBasedCardGame(PokerRules())
            
            assertEquals("Texas Hold'em Poker", game.getStrategy().gameName)
            
            game.setStrategy(BlackjackRules())
            
            assertEquals("Blackjack", game.getStrategy().gameName)
        }
        
        @Test
        fun `adds players correctly`() {
            val game = StrategyBasedCardGame(PokerRules())
            
            assertTrue(game.addPlayer(Player("p1", "Player 1")))
            assertTrue(game.addPlayer(Player("p2", "Player 2")))
            
            assertEquals(2, game.getPlayers().size)
        }
        
        @Test
        fun `rejects duplicate players`() {
            val game = StrategyBasedCardGame(PokerRules())
            
            assertTrue(game.addPlayer(Player("p1", "Player 1")))
            assertFalse(game.addPlayer(Player("p1", "Same ID")))
        }
        
        @Test
        fun `starts game and deals cards`() {
            val game = StrategyBasedCardGame(PokerRules())
            game.addPlayer(Player("p1", "Player 1"))
            game.addPlayer(Player("p2", "Player 2"))
            game.setDeck(Standard52DeckFactory().createDeck())
            
            val result = game.startGame()
            
            assertTrue(result is ActionResult.Success)
            assertEquals(2, game.getHand("p1")?.size)
            assertEquals(2, game.getHand("p2")?.size)
        }
    }

    @Nested
    inner class FactoryBasedGameTests {
        
        @Test
        fun `initializes deck from factory`() {
            val factory = Standard52DeckFactory()
            val game = FactoryBasedCardGame(factory)
            
            game.initializeDeck()
            
            assertEquals(52, game.getDeck().size)
        }
        
        @Test
        fun `deals cards to players`() {
            val game = FactoryBasedCardGame(Standard52DeckFactory())
            game.addPlayer(Player("p1", "Player 1"))
            game.addPlayer(Player("p2", "Player 2"))
            game.initializeDeck()
            
            val hands = game.dealCards(5)
            
            assertEquals(2, hands.size)
            assertEquals(5, hands["p1"]?.size)
            assertEquals(5, hands["p2"]?.size)
            assertEquals(42, game.getDeck().size)
        }
        
        @Test
        fun `player draws card from deck`() {
            val game = FactoryBasedCardGame(Standard52DeckFactory())
            game.addPlayer(Player("p1", "Player 1"))
            game.initializeDeck()
            game.dealCards(2)
            
            val drawn = game.playerDraws("p1", 1)
            
            assertEquals(1, drawn.size)
            assertEquals(3, game.getHand("p1")?.size)
        }
        
        @Test
        fun `player discards card to pile`() {
            val game = FactoryBasedCardGame(Standard52DeckFactory())
            game.addPlayer(Player("p1", "Player 1"))
            game.initializeDeck()
            game.dealCards(5)
            
            val discarded = game.playerDiscards("p1", 0)
            
            assertNotNull(discarded)
            assertEquals(4, game.getHand("p1")?.size)
            assertEquals(1, game.getDiscardPile().size)
        }
        
        @Test
        fun `resets game correctly`() {
            val game = FactoryBasedCardGame(Standard52DeckFactory())
            game.addPlayer(Player("p1", "Player 1"))
            game.initializeDeck()
            game.dealCards(5)
            game.playerDiscards("p1", 0)
            
            game.resetGame()
            
            assertEquals(52, game.getDeck().size)
            assertNull(game.getHand("p1"))
            assertTrue(game.getDiscardPile().isEmpty())
        }
    }

    @Nested
    inner class BlackjackGameTests {
        
        @Test
        fun `starts round and deals cards`() {
            val game = BlackjackGame()
            game.addPlayer("Alice")
            
            game.startRound()
            
            val playerHand = game.getPlayerHand("Alice")
            val dealerHand = game.getDealerHand()
            
            assertNotNull(playerHand)
            assertEquals(2, playerHand!!.size)
            assertEquals(2, dealerHand.size)
        }
        
        @Test
        fun `hit adds card to hand`() {
            val game = BlackjackGame()
            game.addPlayer("Alice")
            game.startRound()
            
            val card = game.hit("Alice")
            
            assertNotNull(card)
            assertEquals(3, game.getPlayerHand("Alice")?.size)
        }
        
        @Test
        fun `calculates hand value correctly`() {
            val game = BlackjackGame()
            val hand = Hand<Card>("test")
            hand.addCards(listOf(
                Card(Suit.HEARTS, Rank.TEN),
                Card(Suit.DIAMONDS, Rank.SEVEN)
            ))
            
            assertEquals(17, game.calculateHandValue(hand))
        }
        
        @Test
        fun `detects blackjack`() {
            val game = BlackjackGame()
            val hand = Hand<Card>("test")
            hand.addCards(listOf(
                Card(Suit.HEARTS, Rank.ACE),
                Card(Suit.DIAMONDS, Rank.JACK)
            ))
            
            assertTrue(game.isBlackjack(hand))
        }
        
        @Test
        fun `detects bust`() {
            val game = BlackjackGame()
            val hand = Hand<Card>("test")
            hand.addCards(listOf(
                Card(Suit.HEARTS, Rank.TEN),
                Card(Suit.DIAMONDS, Rank.EIGHT),
                Card(Suit.CLUBS, Rank.FIVE)
            ))
            
            assertTrue(game.isBust(hand))
        }
    }

    @Nested
    inner class PlayerTests {
        
        @Test
        fun `player can bet chips`() {
            val player = Player("p1", "Player 1", chips = 100)
            
            val success = player.bet(30)
            
            assertTrue(success)
            assertEquals(70, player.chips)
        }
        
        @Test
        fun `player cannot bet more than they have`() {
            val player = Player("p1", "Player 1", chips = 100)
            
            val success = player.bet(150)
            
            assertFalse(success)
            assertEquals(100, player.chips)
        }
        
        @Test
        fun `player wins chips`() {
            val player = Player("p1", "Player 1", chips = 100)
            
            player.win(50)
            
            assertEquals(150, player.chips)
        }
        
        @Test
        fun `player can fold`() {
            val player = Player("p1", "Player 1")
            
            player.fold()
            
            assertTrue(player.hasFolded)
        }
        
        @Test
        fun `player reset clears fold state`() {
            val player = Player("p1", "Player 1")
            player.fold()
            
            player.reset()
            
            assertFalse(player.hasFolded)
        }
    }

    @Nested
    inner class GameObserverTests {
        
        @Test
        fun `notifies observers of events`() {
            val events = mutableListOf<GameEvent>()
            val observer = object : GameObserver {
                override fun onGameEvent(event: GameEvent) {
                    events.add(event)
                }
            }
            
            val game = StrategyBasedCardGame(PokerRules())
            game.addObserver(observer)
            game.addPlayer(Player("p1", "Player 1"))
            game.addPlayer(Player("p2", "Player 2"))
            game.setDeck(Standard52DeckFactory().createDeck())
            
            game.startGame()
            
            assertTrue(events.isNotEmpty())
            assertTrue(events.any { it is GameEvent.PhaseChanged })
            assertTrue(events.any { it is GameEvent.CardDealt })
        }
        
        @Test
        fun `can remove observer`() {
            val events = mutableListOf<GameEvent>()
            val observer = object : GameObserver {
                override fun onGameEvent(event: GameEvent) {
                    events.add(event)
                }
            }
            
            val game = StrategyBasedCardGame(PokerRules())
            game.addObserver(observer)
            game.removeObserver(observer)
            game.addPlayer(Player("p1", "Player 1"))
            game.addPlayer(Player("p2", "Player 2"))
            game.setDeck(Standard52DeckFactory().createDeck())
            
            game.startGame()
            
            assertTrue(events.isEmpty())
        }
    }

    @Nested
    inner class DeckFactoryRegistryTests {
        
        @Test
        fun `registry contains default factories`() {
            val types = DeckFactoryRegistry.getAvailableTypes()
            
            assertTrue(types.contains(DeckType.STANDARD_52))
            assertTrue(types.contains(DeckType.STANDARD_54_WITH_JOKERS))
            assertTrue(types.contains(DeckType.UNO))
        }
        
        @Test
        fun `can retrieve factory by type`() {
            val factory: DeckFactory<Card>? = DeckFactoryRegistry.getFactory(DeckType.STANDARD_52)
            
            assertNotNull(factory)
            assertEquals(52, factory!!.deckSize)
        }
    }

    @Nested
    inner class CardModelTests {
        
        @Test
        fun `card has correct properties`() {
            val card = Card(Suit.HEARTS, Rank.ACE)
            
            assertEquals(Suit.HEARTS, card.suit)
            assertEquals(Rank.ACE, card.rank)
            assertFalse(card.isFaceCard)
        }
        
        @Test
        fun `face cards identified correctly`() {
            val jack = Card(Suit.SPADES, Rank.JACK)
            val queen = Card(Suit.HEARTS, Rank.QUEEN)
            val king = Card(Suit.DIAMONDS, Rank.KING)
            val ten = Card(Suit.CLUBS, Rank.TEN)
            
            assertTrue(jack.isFaceCard)
            assertTrue(queen.isFaceCard)
            assertTrue(king.isFaceCard)
            assertFalse(ten.isFaceCard)
        }
        
        @Test
        fun `suit has correct color`() {
            assertEquals(CardColor.RED, Suit.HEARTS.color())
            assertEquals(CardColor.RED, Suit.DIAMONDS.color())
            assertEquals(CardColor.BLACK, Suit.CLUBS.color())
            assertEquals(CardColor.BLACK, Suit.SPADES.color())
        }
        
        @Test
        fun `rank has correct blackjack value`() {
            assertEquals(11, Rank.ACE.blackjackValue())
            assertEquals(10, Rank.KING.blackjackValue())
            assertEquals(10, Rank.QUEEN.blackjackValue())
            assertEquals(10, Rank.JACK.blackjackValue())
            assertEquals(10, Rank.TEN.blackjackValue())
            assertEquals(5, Rank.FIVE.blackjackValue())
        }
    }
}
