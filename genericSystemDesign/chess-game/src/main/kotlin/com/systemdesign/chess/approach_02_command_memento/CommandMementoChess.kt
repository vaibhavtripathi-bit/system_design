package com.systemdesign.chess.approach_02_command_memento

import com.systemdesign.chess.common.*
import com.systemdesign.chess.approach_01_strategy_movement.Board
import com.systemdesign.chess.approach_01_strategy_movement.MovementStrategyRegistry

/**
 * Approach 2: Command + Memento Pattern for Undo
 * 
 * Moves are encapsulated as commands that can be executed and undone.
 * Board state is captured as mementos for restoration.
 * 
 * Pattern: Command + Memento
 * 
 * Trade-offs:
 * + Full move history with undo/redo support
 * + Game replay capability
 * + State can be saved and restored
 * - Memory overhead for storing mementos
 * - Complex state management
 * 
 * When to use:
 * - When undo/redo is required
 * - When game replay is needed
 * - When move history analysis is important
 * 
 * Extensibility:
 * - New command types: Implement ChessCommand interface
 * - Persistent storage: Serialize mementos
 */

/** Memento for board state */
data class BoardMemento(
    val pieces: Map<Position, Piece>,
    val currentTurn: Color,
    val castlingRights: CastlingRights,
    val enPassantTarget: Position?,
    val halfMoveClock: Int,
    val fullMoveNumber: Int
)

/** Chess command interface */
interface ChessCommand {
    val move: Move
    fun execute(game: CommandMementoGame): Boolean
    fun undo(game: CommandMementoGame)
}

/** Standard move command */
class MoveCommand(
    override val move: Move,
    private var memento: BoardMemento? = null
) : ChessCommand {
    
    override fun execute(game: CommandMementoGame): Boolean {
        memento = game.createMemento()
        return game.executeMove(move)
    }
    
    override fun undo(game: CommandMementoGame) {
        memento?.let { game.restoreFromMemento(it) }
    }
}

/** Castling command */
class CastlingCommand(
    override val move: Move,
    private val isKingSide: Boolean,
    private var memento: BoardMemento? = null
) : ChessCommand {
    
    override fun execute(game: CommandMementoGame): Boolean {
        memento = game.createMemento()
        return game.executeCastling(move, isKingSide)
    }
    
    override fun undo(game: CommandMementoGame) {
        memento?.let { game.restoreFromMemento(it) }
    }
}

/** En passant command */
class EnPassantCommand(
    override val move: Move,
    private val capturedPawnPos: Position,
    private var memento: BoardMemento? = null
) : ChessCommand {
    
    override fun execute(game: CommandMementoGame): Boolean {
        memento = game.createMemento()
        return game.executeEnPassant(move, capturedPawnPos)
    }
    
    override fun undo(game: CommandMementoGame) {
        memento?.let { game.restoreFromMemento(it) }
    }
}

/** Promotion command */
class PromotionCommand(
    override val move: Move,
    private val promoteTo: PieceType,
    private var memento: BoardMemento? = null
) : ChessCommand {
    
    override fun execute(game: CommandMementoGame): Boolean {
        memento = game.createMemento()
        return game.executePromotion(move, promoteTo)
    }
    
    override fun undo(game: CommandMementoGame) {
        memento?.let { game.restoreFromMemento(it) }
    }
}

/**
 * Chess game with command/memento pattern
 */
class CommandMementoGame {
    private val board = Board()
    private var currentTurn = Color.WHITE
    private var castlingRights = CastlingRights()
    private var enPassantTarget: Position? = null
    private var halfMoveClock = 0
    private var fullMoveNumber = 1
    private var gameState = GameState.IN_PROGRESS
    
    private val commandHistory = mutableListOf<ChessCommand>()
    private var historyIndex = -1
    private val positionHistory = mutableListOf<String>()
    
    init {
        board.setupInitialPosition()
    }
    
    fun getCurrentTurn(): Color = currentTurn
    fun getGameState(): GameState = gameState
    fun canUndo(): Boolean = historyIndex >= 0
    fun canRedo(): Boolean = historyIndex < commandHistory.size - 1
    
    fun getPiece(pos: Position): Piece? = board.getPiece(pos)
    
    fun createMemento(): BoardMemento {
        val pieces = mutableMapOf<Position, Piece>()
        for (row in 0..7) {
            for (col in 0..7) {
                val pos = Position(row, col)
                board.getPiece(pos)?.let { pieces[pos] = it }
            }
        }
        
        return BoardMemento(
            pieces = pieces,
            currentTurn = currentTurn,
            castlingRights = castlingRights,
            enPassantTarget = enPassantTarget,
            halfMoveClock = halfMoveClock,
            fullMoveNumber = fullMoveNumber
        )
    }
    
    fun restoreFromMemento(memento: BoardMemento) {
        // Clear board
        for (row in 0..7) {
            for (col in 0..7) {
                board.setPiece(Position(row, col), null)
            }
        }
        
        // Restore pieces
        memento.pieces.forEach { (pos, piece) ->
            board.setPiece(pos, piece)
        }
        
        currentTurn = memento.currentTurn
        castlingRights = memento.castlingRights
        enPassantTarget = memento.enPassantTarget
        halfMoveClock = memento.halfMoveClock
        fullMoveNumber = memento.fullMoveNumber
        
        updateGameState()
    }
    
    fun makeMove(from: Position, to: Position, promotionPiece: PieceType? = null): MoveResult {
        val piece = board.getPiece(from)
            ?: return MoveResult.Invalid("No piece at $from")
        
        if (piece.color != currentTurn) {
            return MoveResult.Invalid("Not ${piece.color}'s turn")
        }
        
        val captured = board.getPiece(to)
        
        // Create appropriate command
        val command = when {
            // Castling
            piece.type == PieceType.KING && kotlin.math.abs(to.col - from.col) == 2 -> {
                val isKingSide = to.col > from.col
                CastlingCommand(
                    Move(from, to, piece, isCastling = true),
                    isKingSide
                )
            }
            // En passant
            piece.type == PieceType.PAWN && 
                to == enPassantTarget && 
                captured == null -> {
                val capturedPos = Position(from.row, to.col)
                EnPassantCommand(
                    Move(from, to, piece, board.getPiece(capturedPos), isEnPassant = true),
                    capturedPos
                )
            }
            // Promotion
            piece.type == PieceType.PAWN && (to.row == 0 || to.row == 7) -> {
                PromotionCommand(
                    Move(from, to, piece, captured, isPromotion = true, 
                        promotionPiece = promotionPiece ?: PieceType.QUEEN),
                    promotionPiece ?: PieceType.QUEEN
                )
            }
            // Normal move
            else -> {
                MoveCommand(Move(from, to, piece, captured))
            }
        }
        
        if (!command.execute(this)) {
            return MoveResult.IllegalMove("Invalid move")
        }
        
        // Truncate future history if we're not at the end
        while (commandHistory.size > historyIndex + 1) {
            commandHistory.removeAt(commandHistory.size - 1)
        }
        
        commandHistory.add(command)
        historyIndex = commandHistory.size - 1
        
        return MoveResult.Success(command.move, gameState)
    }
    
    fun undo(): Move? {
        if (!canUndo()) return null
        
        val command = commandHistory[historyIndex]
        command.undo(this)
        historyIndex--
        
        return command.move
    }
    
    fun redo(): Move? {
        if (!canRedo()) return null
        
        historyIndex++
        val command = commandHistory[historyIndex]
        command.execute(this)
        
        return command.move
    }
    
    fun executeMove(move: Move): Boolean {
        if (!isValidMove(move.from, move.to, move.piece)) {
            return false
        }
        
        board.movePiece(move.from, move.to)
        updateAfterMove(move)
        return true
    }
    
    fun executeCastling(move: Move, isKingSide: Boolean): Boolean {
        val piece = board.getPiece(move.from) ?: return false
        
        // Move king
        board.movePiece(move.from, move.to)
        
        // Move rook
        val rookFromCol = if (isKingSide) 7 else 0
        val rookToCol = if (isKingSide) 5 else 3
        board.movePiece(
            Position(move.from.row, rookFromCol),
            Position(move.from.row, rookToCol)
        )
        
        // Revoke castling rights
        castlingRights = castlingRights.revoke(piece.color)
        
        switchTurn()
        updateGameState()
        return true
    }
    
    fun executeEnPassant(move: Move, capturedPos: Position): Boolean {
        board.movePiece(move.from, move.to)
        board.setPiece(capturedPos, null)
        
        switchTurn()
        updateGameState()
        return true
    }
    
    fun executePromotion(move: Move, promoteTo: PieceType): Boolean {
        val piece = board.getPiece(move.from) ?: return false
        
        board.movePiece(move.from, move.to)
        board.setPiece(move.to, StandardPiece(promoteTo, piece.color, hasMoved = true))
        
        switchTurn()
        updateGameState()
        return true
    }
    
    private fun updateAfterMove(move: Move) {
        val piece = move.piece
        
        // Update en passant target
        enPassantTarget = if (piece.type == PieceType.PAWN && 
            kotlin.math.abs(move.to.row - move.from.row) == 2) {
            Position((move.from.row + move.to.row) / 2, move.from.col)
        } else null
        
        // Update castling rights
        if (piece.type == PieceType.KING) {
            castlingRights = castlingRights.revoke(piece.color)
        } else if (piece.type == PieceType.ROOK) {
            val kingSide = move.from.col == 7
            castlingRights = castlingRights.revoke(piece.color, kingSide)
        }
        
        // Update clocks
        if (piece.type == PieceType.PAWN || move.capturedPiece != null) {
            halfMoveClock = 0
        } else {
            halfMoveClock++
        }
        
        if (piece.color == Color.BLACK) {
            fullMoveNumber++
        }
        
        switchTurn()
        updateGameState()
    }
    
    private fun switchTurn() {
        currentTurn = currentTurn.opposite()
    }
    
    private fun updateGameState() {
        val inCheck = isKingInCheck(currentTurn)
        val hasValidMoves = hasAnyValidMoves(currentTurn)
        
        gameState = when {
            inCheck && !hasValidMoves -> GameState.CHECKMATE
            !inCheck && !hasValidMoves -> GameState.STALEMATE
            halfMoveClock >= 100 -> GameState.DRAW_BY_FIFTY_MOVE
            inCheck -> GameState.CHECK
            else -> GameState.IN_PROGRESS
        }
    }
    
    private fun isValidMove(from: Position, to: Position, piece: Piece): Boolean {
        val strategy = MovementStrategyRegistry.getStrategy(piece.type)
        val validMoves = strategy.getValidMoves(piece, from, board)
        
        if (to !in validMoves) return false
        
        // Check if move leaves king in check
        val testBoard = board.copy()
        testBoard.movePiece(from, to)
        return !isKingInCheckOnBoard(testBoard, piece.color)
    }
    
    private fun isKingInCheck(color: Color): Boolean {
        return isKingInCheckOnBoard(board, color)
    }
    
    private fun isKingInCheckOnBoard(testBoard: Board, color: Color): Boolean {
        val kingPos = testBoard.findKing(color) ?: return false
        val enemyColor = color.opposite()
        
        for ((pos, piece) in testBoard.getAllPieces(enemyColor)) {
            val strategy = MovementStrategyRegistry.getStrategy(piece.type)
            if (strategy.canCapture(piece, pos, kingPos, testBoard)) {
                return true
            }
        }
        
        return false
    }
    
    private fun hasAnyValidMoves(color: Color): Boolean {
        for ((from, piece) in board.getAllPieces(color)) {
            val strategy = MovementStrategyRegistry.getStrategy(piece.type)
            val moves = strategy.getValidMoves(piece, from, board)
            
            for (to in moves) {
                val testBoard = board.copy()
                testBoard.movePiece(from, to)
                if (!isKingInCheckOnBoard(testBoard, color)) {
                    return true
                }
            }
        }
        return false
    }
    
    fun getMoveHistory(): List<Move> {
        return commandHistory.take(historyIndex + 1).map { it.move }
    }
}
