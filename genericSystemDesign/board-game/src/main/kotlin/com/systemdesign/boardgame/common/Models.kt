package com.systemdesign.boardgame.common

/**
 * Core domain models for Generic Multiplayer Board Game Engine.
 * 
 * Extensibility Points:
 * - New game types: Implement GameRulesStrategy interface
 * - New piece types: Add to piece type system
 * - New board sizes: No code changes needed (data-driven)
 * 
 * Breaking Changes Required For:
 * - Changing state machine structure
 * - Adding networked multiplayer support
 */

/** Player in the game */
data class Player(
    val id: String,
    val name: String
) {
    override fun toString(): String = name
}

/** Position on the board */
data class Position(
    val row: Int,
    val col: Int
) {
    fun isValid(board: Board): Boolean =
        row in 0 until board.rows && col in 0 until board.cols
    
    fun offset(rowDelta: Int, colDelta: Int): Position =
        Position(row + rowDelta, col + colDelta)
    
    override fun toString(): String = "($row, $col)"
}

/** A piece on the board */
data class Piece(
    val id: String,
    val type: String,
    val owner: Player,
    val position: Position
) {
    fun moveTo(newPosition: Position): Piece = copy(position = newPosition)
}

/** Game board with pieces */
data class Board(
    val rows: Int,
    val cols: Int,
    val pieces: MutableMap<Position, Piece> = mutableMapOf()
) {
    fun getPiece(position: Position): Piece? = pieces[position]
    
    fun setPiece(position: Position, piece: Piece) {
        pieces[position] = piece
    }
    
    fun removePiece(position: Position): Piece? = pieces.remove(position)
    
    fun movePiece(from: Position, to: Position): Boolean {
        val piece = pieces.remove(from) ?: return false
        pieces[to] = piece.moveTo(to)
        return true
    }
    
    fun isOccupied(position: Position): Boolean = pieces.containsKey(position)
    
    fun isEmpty(position: Position): Boolean = !isOccupied(position)
    
    fun getPiecesByPlayer(player: Player): List<Piece> =
        pieces.values.filter { it.owner == player }
    
    fun getAllPieces(): List<Piece> = pieces.values.toList()
    
    fun clear() = pieces.clear()
    
    fun copy(): Board {
        val newBoard = Board(rows, cols)
        pieces.forEach { (pos, piece) -> newBoard.pieces[pos] = piece }
        return newBoard
    }
    
    fun isValidPosition(position: Position): Boolean =
        position.row in 0 until rows && position.col in 0 until cols
    
    override fun toString(): String {
        val sb = StringBuilder()
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val piece = pieces[Position(row, col)]
                sb.append(piece?.type?.first() ?: '.')
                sb.append(' ')
            }
            sb.appendLine()
        }
        return sb.toString()
    }
}

/** A move in the game */
data class Move(
    val piece: Piece,
    val from: Position,
    val to: Position,
    val timestamp: Long = System.currentTimeMillis(),
    val capturedPiece: Piece? = null,
    val metadata: Map<String, Any> = emptyMap()
) {
    override fun toString(): String = "${piece.type} from $from to $to"
}

/** Sealed class representing game states */
sealed class GameState {
    object Waiting : GameState() {
        override fun toString(): String = "WAITING_FOR_PLAYERS"
    }
    
    data class InProgress(val currentPlayer: Player) : GameState() {
        override fun toString(): String = "IN_PROGRESS (${currentPlayer.name}'s turn)"
    }
    
    data class BetweenTurns(val previousPlayer: Player, val nextPlayer: Player) : GameState() {
        override fun toString(): String = "BETWEEN_TURNS (${previousPlayer.name} -> ${nextPlayer.name})"
    }
    
    data class Finished(val winner: Player?) : GameState() {
        override fun toString(): String = winner?.let { "FINISHED (Winner: ${it.name})" } ?: "FINISHED (Draw)"
    }
}

/** Result of making a move */
sealed class MoveResult {
    data class Success(val move: Move, val newState: GameState) : MoveResult()
    data class InvalidMove(val reason: String) : MoveResult()
    data class NotYourTurn(val currentPlayer: Player) : MoveResult()
    data class GameNotStarted(val message: String = "Game has not started") : MoveResult()
    data class GameAlreadyOver(val winner: Player?) : MoveResult()
}

/** Result of a game action */
sealed class GameActionResult {
    data class Success(val message: String) : GameActionResult()
    data class Failure(val reason: String) : GameActionResult()
}

/** Observer for game events */
interface GameObserver {
    fun onGameStarted(players: List<Player>)
    fun onTurnStarted(player: Player)
    fun onMoveMade(move: Move)
    fun onTurnEnded(player: Player)
    fun onGameEnded(winner: Player?)
    fun onPlayerJoined(player: Player)
    fun onInvalidMove(player: Player, reason: String)
}

/** Direction enum for movement patterns */
enum class Direction(val rowDelta: Int, val colDelta: Int) {
    UP(-1, 0),
    DOWN(1, 0),
    LEFT(0, -1),
    RIGHT(0, 1),
    UP_LEFT(-1, -1),
    UP_RIGHT(-1, 1),
    DOWN_LEFT(1, -1),
    DOWN_RIGHT(1, 1)
}

/** Game configuration */
data class GameConfig(
    val minPlayers: Int = 2,
    val maxPlayers: Int = 2,
    val boardRows: Int = 8,
    val boardCols: Int = 8,
    val turnTimeLimit: Long? = null,
    val enableUndo: Boolean = true,
    val maxUndoMoves: Int = 10
)
