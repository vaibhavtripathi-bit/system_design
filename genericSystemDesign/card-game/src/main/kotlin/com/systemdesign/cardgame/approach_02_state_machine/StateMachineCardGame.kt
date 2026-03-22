package com.systemdesign.cardgame.approach_02_state_machine

import com.systemdesign.cardgame.common.*
import com.systemdesign.cardgame.approach_01_strategy_rules.HandEvaluation

/**
 * Approach 2: State Machine Pattern for Game Phases
 * 
 * Card games have distinct phases with specific valid actions in each phase.
 * The State Machine pattern models these phases explicitly, preventing invalid
 * actions and managing transitions cleanly.
 * 
 * Pattern: State Machine / Finite State Automaton
 * 
 * Trade-offs:
 * + Clear phase transitions prevent invalid operations
 * + Easy to add logging/auditing of phase changes
 * + Phase-specific behavior is encapsulated
 * - State explosion with many orthogonal concerns
 * - Transitions must be carefully designed
 * 
 * When to use:
 * - When game has clear discrete phases
 * - When actions are only valid in certain phases
 * - When you need to track/audit phase history
 * 
 * Extensibility:
 * - New phase: Add to enum and update transition table
 * - New action: Add handler in appropriate phase
 */

/** Detailed game states for poker-style game */
enum class DetailedGameState {
    WAITING_FOR_PLAYERS,
    DEALING,
    PRE_FLOP_BETTING,
    DEALING_FLOP,
    FLOP_BETTING,
    DEALING_TURN,
    TURN_BETTING,
    DEALING_RIVER,
    RIVER_BETTING,
    SHOWDOWN,
    DISTRIBUTING_POT,
    ROUND_OVER,
    GAME_OVER
}

/** Betting action types */
enum class BettingAction {
    FOLD, CHECK, CALL, RAISE, ALL_IN
}

/** State machine based card game */
class StateMachineCardGame(
    private val config: GameConfig = GameConfig()
) {
    private var currentState = DetailedGameState.WAITING_FOR_PLAYERS
    private val players = mutableListOf<Player>()
    private var deck = Deck<Card>(mutableListOf())
    private val hands = mutableMapOf<String, Hand<Card>>()
    private val communityCards = mutableListOf<Card>()
    
    private var pot = 0
    private var currentBet = 0
    private var smallBlindPos = 0
    private var currentPlayerIndex = 0
    private var lastRaiserIndex = -1
    private val playerBets = mutableMapOf<String, Int>()
    
    private val observers = mutableListOf<GameObserver>()
    private val stateHistory = mutableListOf<DetailedGameState>()
    
    private val validTransitions = mapOf(
        DetailedGameState.WAITING_FOR_PLAYERS to setOf(
            DetailedGameState.DEALING
        ),
        DetailedGameState.DEALING to setOf(
            DetailedGameState.PRE_FLOP_BETTING,
            DetailedGameState.GAME_OVER
        ),
        DetailedGameState.PRE_FLOP_BETTING to setOf(
            DetailedGameState.DEALING_FLOP,
            DetailedGameState.SHOWDOWN,
            DetailedGameState.ROUND_OVER
        ),
        DetailedGameState.DEALING_FLOP to setOf(
            DetailedGameState.FLOP_BETTING
        ),
        DetailedGameState.FLOP_BETTING to setOf(
            DetailedGameState.DEALING_TURN,
            DetailedGameState.SHOWDOWN,
            DetailedGameState.ROUND_OVER
        ),
        DetailedGameState.DEALING_TURN to setOf(
            DetailedGameState.TURN_BETTING
        ),
        DetailedGameState.TURN_BETTING to setOf(
            DetailedGameState.DEALING_RIVER,
            DetailedGameState.SHOWDOWN,
            DetailedGameState.ROUND_OVER
        ),
        DetailedGameState.DEALING_RIVER to setOf(
            DetailedGameState.RIVER_BETTING
        ),
        DetailedGameState.RIVER_BETTING to setOf(
            DetailedGameState.SHOWDOWN,
            DetailedGameState.ROUND_OVER
        ),
        DetailedGameState.SHOWDOWN to setOf(
            DetailedGameState.DISTRIBUTING_POT
        ),
        DetailedGameState.DISTRIBUTING_POT to setOf(
            DetailedGameState.ROUND_OVER
        ),
        DetailedGameState.ROUND_OVER to setOf(
            DetailedGameState.DEALING,
            DetailedGameState.GAME_OVER
        )
    )
    
    fun getState(): DetailedGameState = currentState
    fun getPlayers(): List<Player> = players.toList()
    fun getActivePlayers(): List<Player> = players.filter { it.isActive && !it.hasFolded }
    fun getHand(playerId: String): Hand<Card>? = hands[playerId]
    fun getCommunityCards(): List<Card> = communityCards.toList()
    fun getPot(): Int = pot
    fun getCurrentBet(): Int = currentBet
    fun getCurrentPlayer(): Player? = getActivePlayers().getOrNull(currentPlayerIndex)
    fun getStateHistory(): List<DetailedGameState> = stateHistory.toList()
    
    private fun canTransition(to: DetailedGameState): Boolean {
        return validTransitions[currentState]?.contains(to) == true
    }
    
    private fun transition(to: DetailedGameState): Boolean {
        if (!canTransition(to)) return false
        val from = currentState
        stateHistory.add(from)
        currentState = to
        notifyEvent(GameEvent.PhaseChanged(toGamePhase(from), toGamePhase(to)))
        return true
    }
    
    private fun toGamePhase(state: DetailedGameState): GamePhase = when (state) {
        DetailedGameState.WAITING_FOR_PLAYERS -> GamePhase.Setup
        DetailedGameState.DEALING, DetailedGameState.DEALING_FLOP,
        DetailedGameState.DEALING_TURN, DetailedGameState.DEALING_RIVER -> GamePhase.Dealing
        DetailedGameState.PRE_FLOP_BETTING, DetailedGameState.FLOP_BETTING,
        DetailedGameState.TURN_BETTING, DetailedGameState.RIVER_BETTING -> GamePhase.Playing
        DetailedGameState.SHOWDOWN, DetailedGameState.DISTRIBUTING_POT -> GamePhase.Showdown
        DetailedGameState.ROUND_OVER, DetailedGameState.GAME_OVER -> GamePhase.GameOver
    }
    
    fun addPlayer(player: Player): ActionResult {
        if (currentState != DetailedGameState.WAITING_FOR_PLAYERS) {
            return ActionResult.InvalidPhase(toGamePhase(currentState), GamePhase.Setup)
        }
        if (players.size >= config.maxPlayers) {
            return ActionResult.InvalidAction("Maximum players reached")
        }
        if (players.any { it.id == player.id }) {
            return ActionResult.InvalidAction("Player already exists")
        }
        
        players.add(player.copy(chips = config.startingChips))
        return ActionResult.Success("Player ${player.name} joined")
    }
    
    fun removePlayer(playerId: String): ActionResult {
        val player = players.find { it.id == playerId }
            ?: return ActionResult.PlayerNotFound(playerId)
        
        if (currentState == DetailedGameState.WAITING_FOR_PLAYERS) {
            players.remove(player)
            return ActionResult.Success("Player removed")
        }
        
        player.isActive = false
        player.hasFolded = true
        notifyEvent(GameEvent.PlayerEliminated(playerId))
        
        checkForSinglePlayerRemaining()
        return ActionResult.Success("Player eliminated")
    }
    
    fun startGame(): ActionResult {
        if (currentState != DetailedGameState.WAITING_FOR_PLAYERS) {
            return ActionResult.InvalidPhase(toGamePhase(currentState), GamePhase.Setup)
        }
        if (players.size < config.minPlayers) {
            return ActionResult.InvalidAction("Need at least ${config.minPlayers} players")
        }
        
        transition(DetailedGameState.DEALING)
        return startNewRound()
    }
    
    private fun startNewRound(): ActionResult {
        players.forEach { 
            it.hasFolded = false 
            it.isActive = it.chips > 0
        }
        
        hands.clear()
        communityCards.clear()
        playerBets.clear()
        pot = 0
        currentBet = 0
        
        deck = createStandardDeck()
        deck.shuffle()
        
        dealHoleCards()
        collectBlinds()
        
        currentPlayerIndex = (smallBlindPos + 2) % getActivePlayers().size
        lastRaiserIndex = -1
        
        transition(DetailedGameState.PRE_FLOP_BETTING)
        return ActionResult.Success("New round started")
    }
    
    private fun createStandardDeck(): Deck<Card> {
        val cards = mutableListOf<Card>()
        Suit.entries.forEach { suit ->
            Rank.entries.forEach { rank ->
                cards.add(Card(suit, rank))
            }
        }
        return Deck(cards)
    }
    
    private fun dealHoleCards() {
        getActivePlayers().forEach { player ->
            val hand = Hand<Card>(player.id)
            repeat(config.cardsPerPlayer) {
                deck.draw()?.let { hand.addCard(it) }
            }
            hands[player.id] = hand
            notifyEvent(GameEvent.CardDealt(player.id, config.cardsPerPlayer))
        }
    }
    
    private fun collectBlinds() {
        val activePlayers = getActivePlayers()
        if (activePlayers.size < 2) return
        
        val smallBlindPlayer = activePlayers[smallBlindPos % activePlayers.size]
        val bigBlindPlayer = activePlayers[(smallBlindPos + 1) % activePlayers.size]
        
        placeBet(smallBlindPlayer.id, config.smallBlind, isForcedBet = true)
        placeBet(bigBlindPlayer.id, config.bigBlind, isForcedBet = true)
        
        currentBet = config.bigBlind
    }
    
    private fun placeBet(playerId: String, amount: Int, isForcedBet: Boolean = false): Boolean {
        val player = players.find { it.id == playerId } ?: return false
        val actualAmount = minOf(amount, player.chips)
        
        if (!isForcedBet && actualAmount < amount && actualAmount != player.chips) {
            return false
        }
        
        player.chips -= actualAmount
        pot += actualAmount
        playerBets[playerId] = (playerBets[playerId] ?: 0) + actualAmount
        
        notifyEvent(GameEvent.BetPlaced(playerId, actualAmount))
        notifyEvent(GameEvent.PotUpdated(pot))
        
        return true
    }
    
    fun performAction(playerId: String, action: BettingAction, amount: Int = 0): ActionResult {
        if (!isBettingPhase()) {
            return ActionResult.InvalidPhase(toGamePhase(currentState), GamePhase.Playing)
        }
        
        val currentPlayer = getCurrentPlayer()
            ?: return ActionResult.InvalidAction("No current player")
        
        if (currentPlayer.id != playerId) {
            return ActionResult.InvalidAction("Not your turn")
        }
        
        val result = when (action) {
            BettingAction.FOLD -> handleFold(playerId)
            BettingAction.CHECK -> handleCheck(playerId)
            BettingAction.CALL -> handleCall(playerId)
            BettingAction.RAISE -> handleRaise(playerId, amount)
            BettingAction.ALL_IN -> handleAllIn(playerId)
        }
        
        if (result is ActionResult.Success) {
            advanceToNextPlayer()
        }
        
        return result
    }
    
    private fun isBettingPhase(): Boolean = currentState in listOf(
        DetailedGameState.PRE_FLOP_BETTING,
        DetailedGameState.FLOP_BETTING,
        DetailedGameState.TURN_BETTING,
        DetailedGameState.RIVER_BETTING
    )
    
    private fun handleFold(playerId: String): ActionResult {
        val player = players.find { it.id == playerId }
            ?: return ActionResult.PlayerNotFound(playerId)
        
        player.hasFolded = true
        notifyEvent(GameEvent.PlayerFolded(playerId))
        
        checkForSinglePlayerRemaining()
        return ActionResult.Success("Player folded")
    }
    
    private fun handleCheck(playerId: String): ActionResult {
        val playerBet = playerBets[playerId] ?: 0
        if (playerBet < currentBet) {
            return ActionResult.InvalidAction("Cannot check, must call $currentBet")
        }
        
        return ActionResult.Success("Player checked")
    }
    
    private fun handleCall(playerId: String): ActionResult {
        val player = players.find { it.id == playerId }
            ?: return ActionResult.PlayerNotFound(playerId)
        
        val playerBet = playerBets[playerId] ?: 0
        val toCall = currentBet - playerBet
        
        if (toCall <= 0) {
            return ActionResult.Success("Nothing to call")
        }
        
        if (player.chips < toCall) {
            return handleAllIn(playerId)
        }
        
        placeBet(playerId, toCall)
        return ActionResult.Success("Player called $toCall")
    }
    
    private fun handleRaise(playerId: String, amount: Int): ActionResult {
        val player = players.find { it.id == playerId }
            ?: return ActionResult.PlayerNotFound(playerId)
        
        val playerBet = playerBets[playerId] ?: 0
        val minRaise = currentBet * 2
        val totalBet = playerBet + amount
        
        if (totalBet < minRaise) {
            return ActionResult.InvalidAction("Minimum raise is $minRaise")
        }
        
        if (amount > player.chips) {
            return ActionResult.InsufficientChips(amount, player.chips)
        }
        
        placeBet(playerId, amount)
        currentBet = totalBet
        lastRaiserIndex = getActivePlayers().indexOfFirst { it.id == playerId }
        
        return ActionResult.Success("Player raised to $totalBet")
    }
    
    private fun handleAllIn(playerId: String): ActionResult {
        val player = players.find { it.id == playerId }
            ?: return ActionResult.PlayerNotFound(playerId)
        
        val allInAmount = player.chips
        placeBet(playerId, allInAmount)
        
        val playerBet = playerBets[playerId] ?: 0
        if (playerBet > currentBet) {
            currentBet = playerBet
            lastRaiserIndex = getActivePlayers().indexOfFirst { it.id == playerId }
        }
        
        return ActionResult.Success("Player went all-in with $allInAmount")
    }
    
    private fun advanceToNextPlayer() {
        val activePlayers = getActivePlayers()
        
        if (activePlayers.size <= 1) {
            handleSinglePlayerRemaining()
            return
        }
        
        var nextIndex = (currentPlayerIndex + 1) % activePlayers.size
        
        while (activePlayers[nextIndex].hasFolded || activePlayers[nextIndex].chips == 0) {
            nextIndex = (nextIndex + 1) % activePlayers.size
            if (nextIndex == currentPlayerIndex) break
        }
        
        if (isBettingRoundComplete(nextIndex)) {
            advancePhase()
        } else {
            currentPlayerIndex = nextIndex
        }
    }
    
    private fun isBettingRoundComplete(nextIndex: Int): Boolean {
        val activePlayers = getActivePlayers().filter { !it.hasFolded && it.chips > 0 }
        
        if (activePlayers.size <= 1) return true
        
        val allEvenlyBet = activePlayers.all { 
            (playerBets[it.id] ?: 0) == currentBet || it.chips == 0 
        }
        
        return allEvenlyBet && (lastRaiserIndex == -1 || nextIndex == lastRaiserIndex)
    }
    
    private fun advancePhase() {
        lastRaiserIndex = -1
        currentPlayerIndex = 0
        playerBets.clear()
        
        when (currentState) {
            DetailedGameState.PRE_FLOP_BETTING -> {
                transition(DetailedGameState.DEALING_FLOP)
                dealCommunityCards(3)
                transition(DetailedGameState.FLOP_BETTING)
            }
            DetailedGameState.FLOP_BETTING -> {
                transition(DetailedGameState.DEALING_TURN)
                dealCommunityCards(1)
                transition(DetailedGameState.TURN_BETTING)
            }
            DetailedGameState.TURN_BETTING -> {
                transition(DetailedGameState.DEALING_RIVER)
                dealCommunityCards(1)
                transition(DetailedGameState.RIVER_BETTING)
            }
            DetailedGameState.RIVER_BETTING -> {
                transition(DetailedGameState.SHOWDOWN)
                determineWinner()
            }
            else -> {}
        }
    }
    
    private fun dealCommunityCards(count: Int) {
        repeat(count) {
            deck.draw()?.let { communityCards.add(it) }
        }
    }
    
    private fun checkForSinglePlayerRemaining() {
        val activePlayers = getActivePlayers()
        if (activePlayers.size == 1) {
            handleSinglePlayerRemaining()
        }
    }
    
    private fun handleSinglePlayerRemaining() {
        val winner = getActivePlayers().firstOrNull()
        if (winner != null) {
            winner.chips += pot
            notifyEvent(GameEvent.WinnerDeclared(winner.id, pot, "Last player standing"))
            
            transition(DetailedGameState.DISTRIBUTING_POT)
            transition(DetailedGameState.ROUND_OVER)
            
            pot = 0
            checkForGameOver()
        }
    }
    
    private fun determineWinner() {
        val activePlayers = getActivePlayers()
        
        if (activePlayers.isEmpty()) {
            transition(DetailedGameState.DISTRIBUTING_POT)
            transition(DetailedGameState.ROUND_OVER)
            return
        }
        
        val evaluations: Map<Player, HandEvaluation> = activePlayers.mapNotNull { player ->
            hands[player.id]?.let { hand ->
                val fullHand = Hand<Card>(player.id)
                fullHand.addCards(hand.cards)
                fullHand.addCards(communityCards)
                player to evaluatePokerHand(fullHand)
            }
        }.toMap()
        
        val bestScore = evaluations.values.maxOfOrNull { eval -> eval.score } ?: 0
        val winners = evaluations.filter { (_, eval) -> eval.score == bestScore }.keys.toList()
        
        val winningsPerPlayer = if (winners.isNotEmpty()) pot / winners.size else 0
        winners.forEach { winner: Player ->
            winner.chips += winningsPerPlayer
            notifyEvent(GameEvent.WinnerDeclared(
                winner.id, 
                winningsPerPlayer, 
                evaluations[winner]?.description
            ))
        }
        
        transition(DetailedGameState.DISTRIBUTING_POT)
        transition(DetailedGameState.ROUND_OVER)
        
        pot = 0
        checkForGameOver()
    }
    
    private fun evaluatePokerHand(hand: Hand<Card>): HandEvaluation {
        val cards = hand.cards.sortedByDescending { it.rank.ordinal }
        val bestFive = if (cards.size > 5) selectBestFive(cards) else cards
        
        val isFlush = bestFive.groupBy { it.suit }.any { it.value.size >= 5 }
        val rankGroups = bestFive.groupBy { it.rank }
        val groupSizes = rankGroups.values.map { it.size }.sortedDescending()
        
        val ranks = bestFive.map { it.rank.ordinal }.sorted()
        val isStraight = checkStraight(ranks)
        
        val (score, rankName) = when {
            isFlush && isStraight && ranks.contains(12) -> 1000 to "Royal Flush"
            isFlush && isStraight -> 900 to "Straight Flush"
            groupSizes.firstOrNull() == 4 -> 800 to "Four of a Kind"
            groupSizes.take(2) == listOf(3, 2) -> 700 to "Full House"
            isFlush -> 600 to "Flush"
            isStraight -> 500 to "Straight"
            groupSizes.firstOrNull() == 3 -> 400 to "Three of a Kind"
            groupSizes.take(2) == listOf(2, 2) -> 300 to "Two Pair"
            groupSizes.firstOrNull() == 2 -> 200 to "One Pair"
            else -> 100 to "High Card"
        }
        
        val highCardValue = bestFive.maxOf { it.rank.ordinal }
        
        return HandEvaluation(
            score = score + highCardValue,
            rankName = rankName,
            description = "$rankName: ${bestFive.joinToString(", ")}",
            highCards = bestFive
        )
    }
    
    private fun selectBestFive(cards: List<Card>): List<Card> {
        return cards.take(5)
    }
    
    private fun checkStraight(ranks: List<Int>): Boolean {
        val unique = ranks.distinct().sorted()
        if (unique.size < 5) return false
        
        for (i in 0..unique.size - 5) {
            if (unique[i + 4] - unique[i] == 4) return true
        }
        
        if (unique.containsAll(listOf(0, 1, 2, 3, 12))) return true
        return false
    }
    
    private fun checkForGameOver() {
        val playersWithChips = players.filter { it.chips > 0 }
        
        if (playersWithChips.size <= 1) {
            transition(DetailedGameState.GAME_OVER)
            playersWithChips.firstOrNull()?.let {
                notifyEvent(GameEvent.WinnerDeclared(it.id, it.chips, "Game Winner"))
            }
        } else {
            smallBlindPos = (smallBlindPos + 1) % playersWithChips.size
        }
    }
    
    fun startNextRound(): ActionResult {
        if (currentState != DetailedGameState.ROUND_OVER) {
            return ActionResult.InvalidPhase(toGamePhase(currentState), GamePhase.GameOver)
        }
        
        if (players.count { it.chips > 0 } < config.minPlayers) {
            transition(DetailedGameState.GAME_OVER)
            return ActionResult.GameEnded(
                players.maxByOrNull { it.chips }?.id,
                players.maxOf { it.chips }
            )
        }
        
        transition(DetailedGameState.DEALING)
        return startNewRound()
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

/** Turn-based card game state machine (for simpler games like UNO) */
class TurnBasedStateMachineGame(
    private val config: GameConfig = GameConfig()
) {
    enum class TurnState {
        WAITING_FOR_PLAYERS,
        DEALING,
        PLAYER_TURN,
        DRAWING_CARDS,
        CHECKING_WIN,
        GAME_OVER
    }
    
    private var currentState = TurnState.WAITING_FOR_PLAYERS
    private val players = mutableListOf<Player>()
    private var deck = Deck<Card>(mutableListOf())
    private val hands = mutableMapOf<String, Hand<Card>>()
    private val discardPile = mutableListOf<Card>()
    private var currentPlayerIndex = 0
    private var playDirection = 1
    private val observers = mutableListOf<GameObserver>()
    
    fun getState(): TurnState = currentState
    fun getCurrentPlayer(): Player? = players.getOrNull(currentPlayerIndex)
    fun getTopDiscard(): Card? = discardPile.lastOrNull()
    fun getHand(playerId: String): Hand<Card>? = hands[playerId]
    
    fun addPlayer(player: Player): Boolean {
        if (currentState != TurnState.WAITING_FOR_PLAYERS) return false
        if (players.size >= config.maxPlayers) return false
        
        players.add(player)
        return true
    }
    
    fun startGame(): Boolean {
        if (currentState != TurnState.WAITING_FOR_PLAYERS) return false
        if (players.size < config.minPlayers) return false
        
        currentState = TurnState.DEALING
        
        deck = createStandardDeck()
        deck.shuffle()
        
        players.forEach { player ->
            val hand = Hand<Card>(player.id)
            repeat(7) {
                deck.draw()?.let { hand.addCard(it) }
            }
            hands[player.id] = hand
        }
        
        deck.draw()?.let { discardPile.add(it) }
        
        currentState = TurnState.PLAYER_TURN
        return true
    }
    
    private fun createStandardDeck(): Deck<Card> {
        val cards = mutableListOf<Card>()
        Suit.entries.forEach { suit ->
            Rank.entries.forEach { rank ->
                cards.add(Card(suit, rank))
            }
        }
        return Deck(cards)
    }
    
    fun playCard(playerId: String, cardIndex: Int): ActionResult {
        if (currentState != TurnState.PLAYER_TURN) {
            return ActionResult.InvalidAction("Not in playing phase")
        }
        
        val currentPlayer = getCurrentPlayer()
        if (currentPlayer?.id != playerId) {
            return ActionResult.InvalidAction("Not your turn")
        }
        
        val hand = hands[playerId] ?: return ActionResult.PlayerNotFound(playerId)
        if (cardIndex !in hand.cards.indices) {
            return ActionResult.InvalidAction("Invalid card index")
        }
        
        val card = hand.cards[cardIndex]
        val topCard = discardPile.lastOrNull()
        
        if (topCard != null && !isValidPlay(card, topCard)) {
            return ActionResult.InvalidAction("Card doesn't match")
        }
        
        hand.removeCardAt(cardIndex)
        discardPile.add(card)
        notifyEvent(GameEvent.CardPlayed(playerId, card))
        
        handleSpecialCard(card)
        
        currentState = TurnState.CHECKING_WIN
        if (hand.isEmpty) {
            currentState = TurnState.GAME_OVER
            notifyEvent(GameEvent.WinnerDeclared(playerId, 0, "Empty hand"))
            return ActionResult.GameEnded(playerId)
        }
        
        advanceToNextPlayer()
        currentState = TurnState.PLAYER_TURN
        
        return ActionResult.Success("Played $card")
    }
    
    private fun isValidPlay(card: Card, topCard: Card): Boolean {
        return card.suit == topCard.suit || card.rank == topCard.rank
    }
    
    private fun handleSpecialCard(card: Card) {
        when (card.rank) {
            Rank.JACK -> playDirection *= -1
            Rank.QUEEN -> advanceToNextPlayer()
            else -> {}
        }
    }
    
    fun drawCard(playerId: String): ActionResult {
        if (currentState != TurnState.PLAYER_TURN) {
            return ActionResult.InvalidAction("Not in playing phase")
        }
        
        val currentPlayer = getCurrentPlayer()
        if (currentPlayer?.id != playerId) {
            return ActionResult.InvalidAction("Not your turn")
        }
        
        currentState = TurnState.DRAWING_CARDS
        
        if (deck.isEmpty) {
            reshuffleDeck()
        }
        
        val hand = hands[playerId] ?: return ActionResult.PlayerNotFound(playerId)
        val card = deck.draw() ?: return ActionResult.InvalidAction("No cards to draw")
        
        hand.addCard(card)
        advanceToNextPlayer()
        
        currentState = TurnState.PLAYER_TURN
        
        return ActionResult.Success("Drew a card")
    }
    
    private fun reshuffleDeck() {
        val topCard = discardPile.removeLastOrNull()
        deck.reset(discardPile)
        deck.shuffle()
        discardPile.clear()
        topCard?.let { discardPile.add(it) }
    }
    
    private fun advanceToNextPlayer() {
        currentPlayerIndex = (currentPlayerIndex + playDirection + players.size) % players.size
    }
    
    fun addObserver(observer: GameObserver) {
        observers.add(observer)
    }
    
    private fun notifyEvent(event: GameEvent) {
        observers.forEach { it.onGameEvent(event) }
    }
}
