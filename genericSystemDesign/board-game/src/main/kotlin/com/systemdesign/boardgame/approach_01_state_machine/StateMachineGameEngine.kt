package com.systemdesign.boardgame.approach_01_state_machine

import com.systemdesign.boardgame.common.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Approach 1: State Machine Pattern
 * 
 * The game engine is modeled as an explicit finite state machine.
 * Each state has specific valid operations and transitions.
 * 
 * Pattern: State Machine
 * 
 * Trade-offs:
 * + Clear state transitions prevent invalid operations
 * + Easy to reason about game flow
 * + State-specific error handling
 * + Natural modeling of turn-based gameplay
 * - More boilerplate for state management
 * - State explosion if too many concurrent concerns
 * 
 * When to use:
 * - When game has clear discrete phases
 * - When operations are only valid in certain states
 * - When audit/logging of state transitions is important
 * 
 * Extensibility:
 * - New state: Add to enum and update transition table
 * - New game phase: Add handler in appropriate state
 */

/** Internal state enum for the state machine */
enum class InternalGameState {
    WAITING_FOR_PLAYERS,
    PLAYER_TURN,
    BETWEEN_TURNS,
    GAME_OVER
}

/** State machine-based game engine */
class StateMachineGameEngine(
    private val config: GameConfig = GameConfig(),
    private val moveValidator: MoveValidator = DefaultMoveValidator()
) {
    private var internalState: InternalGameState = InternalGameState.WAITING_FOR_PLAYERS
    private val players = mutableListOf<Player>()
    private var currentPlayerIndex = 0
    private var board = Board(config.boardRows, config.boardCols)
    private val moveHistory = mutableListOf<Move>()
    private var winner: Player? = null
    private val observers = CopyOnWriteArrayList<GameObserver>()
    
    private val validTransitions = mapOf(
        InternalGameState.WAITING_FOR_PLAYERS to setOf(
            InternalGameState.PLAYER_TURN
        ),
        InternalGameState.PLAYER_TURN to setOf(
            InternalGameState.BETWEEN_TURNS,
            InternalGameState.GAME_OVER
        ),
        InternalGameState.BETWEEN_TURNS to setOf(
            InternalGameState.PLAYER_TURN,
            InternalGameState.GAME_OVER
        ),
        InternalGameState.GAME_OVER to emptySet()
    )
    
    fun getState(): GameState = when (internalState) {
        InternalGameState.WAITING_FOR_PLAYERS -> GameState.Waiting
        InternalGameState.PLAYER_TURN -> GameState.InProgress(getCurrentPlayer())
        InternalGameState.BETWEEN_TURNS -> {
            val previous = players[(currentPlayerIndex + players.size - 1) % players.size]
            val next = players[currentPlayerIndex]
            GameState.BetweenTurns(previous, next)
        }
        InternalGameState.GAME_OVER -> GameState.Finished(winner)
    }
    
    fun getPlayers(): List<Player> = players.toList()
    
    fun getCurrentPlayer(): Player = players[currentPlayerIndex]
    
    fun getBoard(): Board = board.copy()
    
    fun getMoveHistory(): List<Move> = moveHistory.toList()
    
    private fun canTransition(to: InternalGameState): Boolean {
        return validTransitions[internalState]?.contains(to) == true
    }
    
    private fun transition(to: InternalGameState): Boolean {
        if (!canTransition(to)) return false
        internalState = to
        return true
    }
    
    fun addPlayer(player: Player): GameActionResult {
        if (internalState != InternalGameState.WAITING_FOR_PLAYERS) {
            return GameActionResult.Failure("Cannot add players after game has started")
        }
        
        if (players.size >= config.maxPlayers) {
            return GameActionResult.Failure("Maximum players (${config.maxPlayers}) already joined")
        }
        
        if (players.any { it.id == player.id }) {
            return GameActionResult.Failure("Player with id ${player.id} already exists")
        }
        
        players.add(player)
        notifyPlayerJoined(player)
        
        return GameActionResult.Success("${player.name} joined the game")
    }
    
    fun startGame(): GameActionResult {
        if (internalState != InternalGameState.WAITING_FOR_PLAYERS) {
            return GameActionResult.Failure("Game already started")
        }
        
        if (players.size < config.minPlayers) {
            return GameActionResult.Failure("Need at least ${config.minPlayers} players to start")
        }
        
        if (!transition(InternalGameState.PLAYER_TURN)) {
            return GameActionResult.Failure("Cannot start game from current state")
        }
        
        setupInitialBoard()
        notifyGameStarted(players)
        notifyTurnStarted(getCurrentPlayer())
        
        return GameActionResult.Success("Game started! ${getCurrentPlayer().name}'s turn")
    }
    
    private fun setupInitialBoard() {
        board.clear()
    }
    
    fun makeMove(player: Player, from: Position, to: Position): MoveResult {
        return when (internalState) {
            InternalGameState.WAITING_FOR_PLAYERS -> 
                MoveResult.GameNotStarted()
            
            InternalGameState.GAME_OVER -> 
                MoveResult.GameAlreadyOver(winner)
            
            InternalGameState.BETWEEN_TURNS -> 
                MoveResult.InvalidMove("Please wait for your turn to begin")
            
            InternalGameState.PLAYER_TURN -> {
                if (player.id != getCurrentPlayer().id) {
                    return MoveResult.NotYourTurn(getCurrentPlayer())
                }
                
                val piece = board.getPiece(from)
                    ?: return MoveResult.InvalidMove("No piece at position $from")
                
                if (piece.owner.id != player.id) {
                    return MoveResult.InvalidMove("That piece doesn't belong to you")
                }
                
                val validationResult = moveValidator.validate(board, piece, from, to)
                if (!validationResult.isValid) {
                    notifyInvalidMove(player, validationResult.reason)
                    return MoveResult.InvalidMove(validationResult.reason)
                }
                
                val capturedPiece = board.getPiece(to)
                val move = Move(
                    piece = piece,
                    from = from,
                    to = to,
                    capturedPiece = capturedPiece
                )
                
                board.removePiece(from)
                if (capturedPiece != null) {
                    board.removePiece(to)
                }
                board.setPiece(to, piece.moveTo(to))
                
                moveHistory.add(move)
                notifyMoveMade(move)
                
                if (checkWinCondition(player)) {
                    winner = player
                    transition(InternalGameState.GAME_OVER)
                    notifyGameEnded(winner)
                    return MoveResult.Success(move, GameState.Finished(winner))
                }
                
                transition(InternalGameState.BETWEEN_TURNS)
                MoveResult.Success(move, getState())
            }
        }
    }
    
    fun endTurn(): GameActionResult {
        if (internalState != InternalGameState.BETWEEN_TURNS && 
            internalState != InternalGameState.PLAYER_TURN) {
            return GameActionResult.Failure("No turn to end")
        }
        
        val previousPlayer = getCurrentPlayer()
        notifyTurnEnded(previousPlayer)
        
        currentPlayerIndex = (currentPlayerIndex + 1) % players.size
        
        if (checkDrawCondition()) {
            winner = null
            transition(InternalGameState.GAME_OVER)
            notifyGameEnded(null)
            return GameActionResult.Success("Game ended in a draw!")
        }
        
        transition(InternalGameState.PLAYER_TURN)
        notifyTurnStarted(getCurrentPlayer())
        
        return GameActionResult.Success("${getCurrentPlayer().name}'s turn")
    }
    
    fun declareWinner(player: Player): GameActionResult {
        if (internalState == InternalGameState.WAITING_FOR_PLAYERS) {
            return GameActionResult.Failure("Game has not started")
        }
        
        if (internalState == InternalGameState.GAME_OVER) {
            return GameActionResult.Failure("Game is already over")
        }
        
        if (!players.contains(player)) {
            return GameActionResult.Failure("Player is not in this game")
        }
        
        winner = player
        transition(InternalGameState.GAME_OVER)
        notifyGameEnded(winner)
        
        return GameActionResult.Success("${player.name} wins!")
    }
    
    fun forfeit(player: Player): GameActionResult {
        if (internalState == InternalGameState.WAITING_FOR_PLAYERS) {
            return GameActionResult.Failure("Game has not started")
        }
        
        if (internalState == InternalGameState.GAME_OVER) {
            return GameActionResult.Failure("Game is already over")
        }
        
        val otherPlayers = players.filter { it.id != player.id }
        winner = if (otherPlayers.size == 1) otherPlayers.first() else null
        
        transition(InternalGameState.GAME_OVER)
        notifyGameEnded(winner)
        
        return GameActionResult.Success("${player.name} forfeited. ${winner?.name ?: "No one"} wins!")
    }
    
    private fun checkWinCondition(player: Player): Boolean {
        val opponent = players.find { it.id != player.id } ?: return false
        return board.getPiecesByPlayer(opponent).isEmpty()
    }
    
    private fun checkDrawCondition(): Boolean {
        return moveHistory.size > 100 && 
               moveHistory.takeLast(20).distinctBy { "${it.from}${it.to}" }.size < 5
    }
    
    fun placePiece(piece: Piece, position: Position): GameActionResult {
        if (!board.isValidPosition(position)) {
            return GameActionResult.Failure("Invalid position: $position")
        }
        
        if (board.isOccupied(position)) {
            return GameActionResult.Failure("Position $position is already occupied")
        }
        
        board.setPiece(position, piece.copy(position = position))
        return GameActionResult.Success("Placed ${piece.type} at $position")
    }
    
    fun reset() {
        internalState = InternalGameState.WAITING_FOR_PLAYERS
        players.clear()
        currentPlayerIndex = 0
        board = Board(config.boardRows, config.boardCols)
        moveHistory.clear()
        winner = null
    }
    
    fun addObserver(observer: GameObserver) {
        observers.add(observer)
    }
    
    fun removeObserver(observer: GameObserver) {
        observers.remove(observer)
    }
    
    private fun notifyGameStarted(players: List<Player>) {
        observers.forEach { it.onGameStarted(players) }
    }
    
    private fun notifyTurnStarted(player: Player) {
        observers.forEach { it.onTurnStarted(player) }
    }
    
    private fun notifyMoveMade(move: Move) {
        observers.forEach { it.onMoveMade(move) }
    }
    
    private fun notifyTurnEnded(player: Player) {
        observers.forEach { it.onTurnEnded(player) }
    }
    
    private fun notifyGameEnded(winner: Player?) {
        observers.forEach { it.onGameEnded(winner) }
    }
    
    private fun notifyPlayerJoined(player: Player) {
        observers.forEach { it.onPlayerJoined(player) }
    }
    
    private fun notifyInvalidMove(player: Player, reason: String) {
        observers.forEach { it.onInvalidMove(player, reason) }
    }
}

/** Move validation result */
data class MoveValidationResult(
    val isValid: Boolean,
    val reason: String = ""
)

/** Interface for move validation */
interface MoveValidator {
    fun validate(board: Board, piece: Piece, from: Position, to: Position): MoveValidationResult
}

/** Default move validator - allows any move to valid positions */
class DefaultMoveValidator : MoveValidator {
    override fun validate(board: Board, piece: Piece, from: Position, to: Position): MoveValidationResult {
        if (!board.isValidPosition(to)) {
            return MoveValidationResult(false, "Destination $to is outside the board")
        }
        
        if (from == to) {
            return MoveValidationResult(false, "Cannot move to the same position")
        }
        
        val targetPiece = board.getPiece(to)
        if (targetPiece != null && targetPiece.owner.id == piece.owner.id) {
            return MoveValidationResult(false, "Cannot capture your own piece")
        }
        
        return MoveValidationResult(true)
    }
}

/** Simple game observer implementation for logging */
class LoggingGameObserver(private val logger: (String) -> Unit = ::println) : GameObserver {
    override fun onGameStarted(players: List<Player>) {
        logger("Game started with players: ${players.joinToString { it.name }}")
    }
    
    override fun onTurnStarted(player: Player) {
        logger("${player.name}'s turn started")
    }
    
    override fun onMoveMade(move: Move) {
        logger("Move: $move")
    }
    
    override fun onTurnEnded(player: Player) {
        logger("${player.name}'s turn ended")
    }
    
    override fun onGameEnded(winner: Player?) {
        logger(winner?.let { "Game over! ${it.name} wins!" } ?: "Game over! It's a draw!")
    }
    
    override fun onPlayerJoined(player: Player) {
        logger("${player.name} joined the game")
    }
    
    override fun onInvalidMove(player: Player, reason: String) {
        logger("Invalid move by ${player.name}: $reason")
    }
}
