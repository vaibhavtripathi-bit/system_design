package com.systemdesign.boardgame.approach_03_command_moves

import com.systemdesign.boardgame.common.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Approach 3: Command Pattern for Move History
 * 
 * Each move is encapsulated as a command object with execute and undo.
 * Enables full undo/redo support and game replay functionality.
 * 
 * Pattern: Command Pattern
 * 
 * Trade-offs:
 * + Full undo/redo support for any game
 * + Move history can be serialized and replayed
 * + Each command is isolated and testable
 * + Easy to implement logging and analytics
 * - Memory overhead for command history
 * - Some moves are hard to undo (random elements, external effects)
 * 
 * When to use:
 * - When undo/redo is a requirement
 * - When game replay is needed (analysis, spectating)
 * - When you need detailed move logging
 * - For turn-based games where moves should be reversible
 * 
 * Extensibility:
 * - New move type: Implement MoveCommand interface
 * - New game action: Create new command class
 * - Composite moves: Use CompositeMoveCommand
 */

/** Interface for move commands */
interface MoveCommand {
    /** Execute the move and return success status */
    fun execute(): Boolean
    
    /** Undo the move and return success status */
    fun undo(): Boolean
    
    /** Get description of the command for logging */
    fun getDescription(): String
    
    /** Get the move data associated with this command */
    fun getMove(): Move?
    
    /** Get the player who made this move */
    fun getPlayer(): Player
}

/** Basic piece move command */
class PieceMoveCommand(
    private val board: Board,
    private val player: Player,
    private val piece: Piece,
    private val from: Position,
    private val to: Position,
    private val validator: MoveCommandValidator
) : MoveCommand {
    
    private var capturedPiece: Piece? = null
    private var executed = false
    private val timestamp = System.currentTimeMillis()
    
    override fun execute(): Boolean {
        if (executed) return false
        
        val validation = validator.validate(board, piece, from, to)
        if (!validation.isValid) return false
        
        capturedPiece = board.getPiece(to)
        
        board.removePiece(from)
        board.setPiece(to, piece.moveTo(to))
        
        executed = true
        return true
    }
    
    override fun undo(): Boolean {
        if (!executed) return false
        
        board.removePiece(to)
        board.setPiece(from, piece)
        
        capturedPiece?.let { board.setPiece(to, it) }
        
        executed = false
        return true
    }
    
    override fun getDescription(): String =
        "${piece.type} moves from $from to $to" + 
        (capturedPiece?.let { " (captures ${it.type})" } ?: "")
    
    override fun getMove(): Move = Move(
        piece = piece,
        from = from,
        to = to,
        timestamp = timestamp,
        capturedPiece = capturedPiece
    )
    
    override fun getPlayer(): Player = player
}

/** Place piece command (for games like Connect Four) */
class PlacePieceCommand(
    private val board: Board,
    private val player: Player,
    private val pieceType: String,
    private val position: Position
) : MoveCommand {
    
    private var placedPiece: Piece? = null
    private var executed = false
    private val timestamp = System.currentTimeMillis()
    
    override fun execute(): Boolean {
        if (executed) return false
        
        if (!board.isValidPosition(position) || board.isOccupied(position)) {
            return false
        }
        
        placedPiece = Piece(
            id = "${player.id}_${timestamp}",
            type = pieceType,
            owner = player,
            position = position
        )
        
        board.setPiece(position, placedPiece!!)
        executed = true
        return true
    }
    
    override fun undo(): Boolean {
        if (!executed) return false
        
        board.removePiece(position)
        executed = false
        return true
    }
    
    override fun getDescription(): String =
        "Place $pieceType at $position"
    
    override fun getMove(): Move? = placedPiece?.let {
        Move(
            piece = it,
            from = position,
            to = position,
            timestamp = timestamp
        )
    }
    
    override fun getPlayer(): Player = player
}

/** Composite command for multi-step moves (like castling) */
class CompositeMoveCommand(
    private val commands: List<MoveCommand>,
    private val player: Player,
    private val description: String
) : MoveCommand {
    
    private var executedCount = 0
    
    override fun execute(): Boolean {
        for (command in commands) {
            if (!command.execute()) {
                rollback()
                return false
            }
            executedCount++
        }
        return true
    }
    
    override fun undo(): Boolean {
        for (i in executedCount - 1 downTo 0) {
            if (!commands[i].undo()) {
                return false
            }
            executedCount--
        }
        return true
    }
    
    private fun rollback() {
        for (i in executedCount - 1 downTo 0) {
            commands[i].undo()
        }
        executedCount = 0
    }
    
    override fun getDescription(): String = description
    
    override fun getMove(): Move? = commands.lastOrNull()?.getMove()
    
    override fun getPlayer(): Player = player
}

/** Command history manager with undo/redo support */
class CommandHistory(private val maxSize: Int = 100) {
    private val undoStack = ArrayDeque<MoveCommand>()
    private val redoStack = ArrayDeque<MoveCommand>()
    private val commandLog = mutableListOf<CommandLogEntry>()
    
    fun execute(command: MoveCommand): Boolean {
        val success = command.execute()
        if (success) {
            undoStack.addLast(command)
            if (undoStack.size > maxSize) {
                undoStack.removeFirst()
            }
            redoStack.clear()
            
            logCommand(command, CommandAction.EXECUTE)
        }
        return success
    }
    
    fun undo(): MoveCommand? {
        if (undoStack.isEmpty()) return null
        
        val command = undoStack.removeLast()
        if (command.undo()) {
            redoStack.addLast(command)
            logCommand(command, CommandAction.UNDO)
            return command
        }
        
        undoStack.addLast(command)
        return null
    }
    
    fun redo(): MoveCommand? {
        if (redoStack.isEmpty()) return null
        
        val command = redoStack.removeLast()
        if (command.execute()) {
            undoStack.addLast(command)
            logCommand(command, CommandAction.REDO)
            return command
        }
        
        redoStack.addLast(command)
        return null
    }
    
    fun canUndo(): Boolean = undoStack.isNotEmpty()
    
    fun canRedo(): Boolean = redoStack.isNotEmpty()
    
    fun getUndoCount(): Int = undoStack.size
    
    fun getRedoCount(): Int = redoStack.size
    
    fun getAllCommands(): List<MoveCommand> = undoStack.toList()
    
    fun getCommandLog(): List<CommandLogEntry> = commandLog.toList()
    
    fun clear() {
        undoStack.clear()
        redoStack.clear()
        commandLog.clear()
    }
    
    private fun logCommand(command: MoveCommand, action: CommandAction) {
        commandLog.add(CommandLogEntry(
            timestamp = System.currentTimeMillis(),
            player = command.getPlayer(),
            description = command.getDescription(),
            action = action
        ))
    }
}

/** Command log entry for replay and analytics */
data class CommandLogEntry(
    val timestamp: Long,
    val player: Player,
    val description: String,
    val action: CommandAction
)

enum class CommandAction {
    EXECUTE, UNDO, REDO
}

/** Interface for move validation in command context */
interface MoveCommandValidator {
    fun validate(board: Board, piece: Piece, from: Position, to: Position): MoveCommandValidation
}

data class MoveCommandValidation(
    val isValid: Boolean,
    val reason: String = ""
)

/** Default validator allowing basic moves */
class DefaultCommandValidator : MoveCommandValidator {
    override fun validate(board: Board, piece: Piece, from: Position, to: Position): MoveCommandValidation {
        if (!board.isValidPosition(to)) {
            return MoveCommandValidation(false, "Invalid destination")
        }
        
        if (from == to) {
            return MoveCommandValidation(false, "Same position")
        }
        
        val target = board.getPiece(to)
        if (target != null && target.owner.id == piece.owner.id) {
            return MoveCommandValidation(false, "Cannot capture own piece")
        }
        
        return MoveCommandValidation(true)
    }
}

/** Command-based game engine */
class CommandMovesGameEngine(
    private val config: GameConfig = GameConfig(),
    private val validator: MoveCommandValidator = DefaultCommandValidator()
) {
    private var state: GameState = GameState.Waiting
    private val players = mutableListOf<Player>()
    private var currentPlayerIndex = 0
    private var board = Board(config.boardRows, config.boardCols)
    private val commandHistory = CommandHistory(config.maxUndoMoves)
    private val observers = CopyOnWriteArrayList<GameObserver>()
    
    fun getState(): GameState = state
    fun getPlayers(): List<Player> = players.toList()
    fun getCurrentPlayer(): Player = players[currentPlayerIndex]
    fun getBoard(): Board = board.copy()
    fun getMoveHistory(): List<Move> = commandHistory.getAllCommands().mapNotNull { it.getMove() }
    fun getCommandLog(): List<CommandLogEntry> = commandHistory.getCommandLog()
    
    fun canUndo(): Boolean = config.enableUndo && commandHistory.canUndo()
    fun canRedo(): Boolean = config.enableUndo && commandHistory.canRedo()
    
    fun addPlayer(player: Player): GameActionResult {
        if (state !is GameState.Waiting) {
            return GameActionResult.Failure("Cannot add players after game has started")
        }
        
        if (players.size >= config.maxPlayers) {
            return GameActionResult.Failure("Maximum players reached")
        }
        
        players.add(player)
        observers.forEach { it.onPlayerJoined(player) }
        return GameActionResult.Success("${player.name} joined")
    }
    
    fun startGame(): GameActionResult {
        if (state !is GameState.Waiting) {
            return GameActionResult.Failure("Game already started")
        }
        
        if (players.size < config.minPlayers) {
            return GameActionResult.Failure("Need at least ${config.minPlayers} players")
        }
        
        state = GameState.InProgress(players[currentPlayerIndex])
        observers.forEach { it.onGameStarted(players) }
        observers.forEach { it.onTurnStarted(getCurrentPlayer()) }
        
        return GameActionResult.Success("Game started!")
    }
    
    fun makeMove(player: Player, from: Position, to: Position): MoveResult {
        val currentState = state
        
        return when (currentState) {
            is GameState.Waiting -> MoveResult.GameNotStarted()
            is GameState.Finished -> MoveResult.GameAlreadyOver(currentState.winner)
            is GameState.BetweenTurns -> MoveResult.InvalidMove("Wait for turn")
            is GameState.InProgress -> {
                if (player.id != currentState.currentPlayer.id) {
                    return MoveResult.NotYourTurn(currentState.currentPlayer)
                }
                
                val piece = board.getPiece(from)
                    ?: return MoveResult.InvalidMove("No piece at $from")
                
                if (piece.owner.id != player.id) {
                    return MoveResult.InvalidMove("That piece belongs to another player")
                }
                
                val command = PieceMoveCommand(board, player, piece, from, to, validator)
                
                if (commandHistory.execute(command)) {
                    val move = command.getMove()!!
                    observers.forEach { it.onMoveMade(move) }
                    
                    if (checkWinCondition(player)) {
                        state = GameState.Finished(player)
                        observers.forEach { it.onGameEnded(player) }
                        return MoveResult.Success(move, state)
                    }
                    
                    advanceTurn()
                    MoveResult.Success(move, state)
                } else {
                    val validation = validator.validate(board, piece, from, to)
                    observers.forEach { it.onInvalidMove(player, validation.reason) }
                    MoveResult.InvalidMove(validation.reason)
                }
            }
        }
    }
    
    fun placePiece(player: Player, pieceType: String, position: Position): MoveResult {
        val currentState = state
        
        return when (currentState) {
            is GameState.Waiting -> MoveResult.GameNotStarted()
            is GameState.Finished -> MoveResult.GameAlreadyOver(currentState.winner)
            is GameState.BetweenTurns -> MoveResult.InvalidMove("Wait for turn")
            is GameState.InProgress -> {
                if (player.id != currentState.currentPlayer.id) {
                    return MoveResult.NotYourTurn(currentState.currentPlayer)
                }
                
                val command = PlacePieceCommand(board, player, pieceType, position)
                
                if (commandHistory.execute(command)) {
                    val move = command.getMove()!!
                    observers.forEach { it.onMoveMade(move) }
                    
                    if (checkWinCondition(player)) {
                        state = GameState.Finished(player)
                        observers.forEach { it.onGameEnded(player) }
                        return MoveResult.Success(move, state)
                    }
                    
                    advanceTurn()
                    MoveResult.Success(move, state)
                } else {
                    MoveResult.InvalidMove("Cannot place piece at $position")
                }
            }
        }
    }
    
    fun undo(): GameActionResult {
        if (!config.enableUndo) {
            return GameActionResult.Failure("Undo is disabled")
        }
        
        if (state is GameState.Finished) {
            val undone = commandHistory.undo()
            if (undone != null) {
                state = GameState.InProgress(undone.getPlayer())
                revertTurn()
                return GameActionResult.Success("Undid: ${undone.getDescription()}")
            }
        }
        
        if (state !is GameState.InProgress) {
            return GameActionResult.Failure("Cannot undo in current state")
        }
        
        val undone = commandHistory.undo()
        return if (undone != null) {
            revertTurn()
            GameActionResult.Success("Undid: ${undone.getDescription()}")
        } else {
            GameActionResult.Failure("Nothing to undo")
        }
    }
    
    fun redo(): GameActionResult {
        if (!config.enableUndo) {
            return GameActionResult.Failure("Redo is disabled")
        }
        
        if (state !is GameState.InProgress && state !is GameState.Finished) {
            return GameActionResult.Failure("Cannot redo in current state")
        }
        
        val redone = commandHistory.redo()
        return if (redone != null) {
            if (checkWinCondition(redone.getPlayer())) {
                state = GameState.Finished(redone.getPlayer())
            } else {
                advanceTurn()
            }
            GameActionResult.Success("Redid: ${redone.getDescription()}")
        } else {
            GameActionResult.Failure("Nothing to redo")
        }
    }
    
    private fun advanceTurn() {
        observers.forEach { it.onTurnEnded(getCurrentPlayer()) }
        currentPlayerIndex = (currentPlayerIndex + 1) % players.size
        state = GameState.InProgress(getCurrentPlayer())
        observers.forEach { it.onTurnStarted(getCurrentPlayer()) }
    }
    
    private fun revertTurn() {
        currentPlayerIndex = (currentPlayerIndex + players.size - 1) % players.size
        state = GameState.InProgress(getCurrentPlayer())
    }
    
    private fun checkWinCondition(player: Player): Boolean {
        val opponent = players.find { it.id != player.id } ?: return false
        // Only check for win if opponent had pieces and now has none (capture-style win)
        // For placement games, both players must have had pieces before considering a win
        val playerPieces = board.getPiecesByPlayer(player)
        val opponentPieces = board.getPiecesByPlayer(opponent)
        
        // Simple capture win: opponent has no pieces AND player has more than 1 piece
        // (meaning there was actually a game played, not just first move)
        return opponentPieces.isEmpty() && playerPieces.size > 1
    }
    
    fun setupPiece(piece: Piece, position: Position): GameActionResult {
        if (!board.isValidPosition(position)) {
            return GameActionResult.Failure("Invalid position")
        }
        
        board.setPiece(position, piece.copy(position = position))
        return GameActionResult.Success("Placed ${piece.type} at $position")
    }
    
    fun replay(onMove: (MoveCommand) -> Unit) {
        commandHistory.getAllCommands().forEach(onMove)
    }
    
    fun addObserver(observer: GameObserver) {
        observers.add(observer)
    }
    
    fun removeObserver(observer: GameObserver) {
        observers.remove(observer)
    }
}

/** Game replay utility */
class GameReplayer(
    private val commands: List<MoveCommand>,
    private val board: Board
) {
    private var currentIndex = 0
    private var executedCount = 0
    
    fun stepForward(): Boolean {
        if (currentIndex >= commands.size) return false
        
        val success = commands[currentIndex].execute()
        if (success) {
            currentIndex++
            executedCount++
        }
        return success
    }
    
    fun stepBackward(): Boolean {
        if (executedCount <= 0) return false
        
        val success = commands[executedCount - 1].undo()
        if (success) {
            currentIndex--
            executedCount--
        }
        return success
    }
    
    fun jumpTo(moveIndex: Int): Boolean {
        if (moveIndex < 0 || moveIndex > commands.size) return false
        
        while (executedCount > moveIndex) {
            if (!stepBackward()) return false
        }
        
        while (executedCount < moveIndex) {
            if (!stepForward()) return false
        }
        
        return true
    }
    
    fun getCurrentMove(): MoveCommand? = 
        if (executedCount > 0) commands[executedCount - 1] else null
    
    fun getTotalMoves(): Int = commands.size
    
    fun getCurrentMoveIndex(): Int = executedCount
    
    fun isAtStart(): Boolean = executedCount == 0
    
    fun isAtEnd(): Boolean = executedCount == commands.size
}
