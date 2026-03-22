package com.systemdesign.chess.common

/**
 * Core domain models for Chess Game Engine.
 * 
 * Extensibility Points:
 * - New piece types: Implement Piece interface
 * - New movement rules: Implement MovementStrategy interface
 * - Chess variants: Different board sizes, piece sets
 * 
 * Breaking Changes Required For:
 * - Changing board representation (2D array to bitboard)
 * - Adding 3D chess support
 */

/** Colors in chess */
enum class Color {
    WHITE, BLACK;
    
    fun opposite(): Color = if (this == WHITE) BLACK else WHITE
}

/** Position on the board (0-indexed) */
data class Position(val row: Int, val col: Int) {
    val isValid: Boolean get() = row in 0..7 && col in 0..7
    
    fun toAlgebraic(): String {
        val file = ('a' + col)
        val rank = (row + 1).toString()
        return "$file$rank"
    }
    
    companion object {
        fun fromAlgebraic(notation: String): Position? {
            if (notation.length != 2) return null
            val col = notation[0] - 'a'
            val rank = notation[1].digitToIntOrNull() ?: return null
            val row = rank - 1
            return if (row in 0..7 && col in 0..7) Position(row, col) else null
        }
    }
}

/** Types of chess pieces */
enum class PieceType {
    KING, QUEEN, ROOK, BISHOP, KNIGHT, PAWN
}

/** A chess piece */
interface Piece {
    val type: PieceType
    val color: Color
    val hasMoved: Boolean
    
    fun copy(hasMoved: Boolean = this.hasMoved): Piece
}

/** Standard chess piece implementation */
data class StandardPiece(
    override val type: PieceType,
    override val color: Color,
    override val hasMoved: Boolean = false
) : Piece {
    override fun copy(hasMoved: Boolean): Piece = StandardPiece(type, color, hasMoved)
    
    override fun toString(): String {
        val symbol = when (type) {
            PieceType.KING -> 'K'
            PieceType.QUEEN -> 'Q'
            PieceType.ROOK -> 'R'
            PieceType.BISHOP -> 'B'
            PieceType.KNIGHT -> 'N'
            PieceType.PAWN -> 'P'
        }
        return if (color == Color.WHITE) symbol.toString() else symbol.lowercaseChar().toString()
    }
}

/** A chess move */
data class Move(
    val from: Position,
    val to: Position,
    val piece: Piece,
    val capturedPiece: Piece? = null,
    val isPromotion: Boolean = false,
    val promotionPiece: PieceType? = null,
    val isCastling: Boolean = false,
    val isEnPassant: Boolean = false,
    val isCheck: Boolean = false,
    val isCheckmate: Boolean = false
) {
    fun toAlgebraic(): String {
        val builder = StringBuilder()
        
        if (isCastling) {
            return if (to.col > from.col) "O-O" else "O-O-O"
        }
        
        if (piece.type != PieceType.PAWN) {
            builder.append(when (piece.type) {
                PieceType.KING -> 'K'
                PieceType.QUEEN -> 'Q'
                PieceType.ROOK -> 'R'
                PieceType.BISHOP -> 'B'
                PieceType.KNIGHT -> 'N'
                else -> ""
            })
        }
        
        if (capturedPiece != null) {
            if (piece.type == PieceType.PAWN) {
                builder.append(from.toAlgebraic()[0])
            }
            builder.append('x')
        }
        
        builder.append(to.toAlgebraic())
        
        if (isPromotion && promotionPiece != null) {
            builder.append('=')
            builder.append(when (promotionPiece) {
                PieceType.QUEEN -> 'Q'
                PieceType.ROOK -> 'R'
                PieceType.BISHOP -> 'B'
                PieceType.KNIGHT -> 'N'
                else -> 'Q'
            })
        }
        
        if (isCheckmate) {
            builder.append('#')
        } else if (isCheck) {
            builder.append('+')
        }
        
        return builder.toString()
    }
}

/** Game state */
enum class GameState {
    IN_PROGRESS,
    CHECK,
    CHECKMATE,
    STALEMATE,
    DRAW_BY_REPETITION,
    DRAW_BY_FIFTY_MOVE,
    DRAW_BY_INSUFFICIENT_MATERIAL,
    RESIGNED,
    DRAW_BY_AGREEMENT
}

/** Result of attempting a move */
sealed class MoveResult {
    data class Success(val move: Move, val newState: GameState) : MoveResult()
    data class Invalid(val reason: String) : MoveResult()
    data class IllegalMove(val reason: String) : MoveResult()
}

/** Board state snapshot for undo/repetition detection */
data class BoardSnapshot(
    val pieces: Map<Position, Piece>,
    val currentTurn: Color,
    val castlingRights: CastlingRights,
    val enPassantTarget: Position?
)

/** Castling rights tracker */
data class CastlingRights(
    val whiteKingSide: Boolean = true,
    val whiteQueenSide: Boolean = true,
    val blackKingSide: Boolean = true,
    val blackQueenSide: Boolean = true
) {
    fun forColor(color: Color, kingSide: Boolean): Boolean {
        return when {
            color == Color.WHITE && kingSide -> whiteKingSide
            color == Color.WHITE && !kingSide -> whiteQueenSide
            color == Color.BLACK && kingSide -> blackKingSide
            else -> blackQueenSide
        }
    }
    
    fun revoke(color: Color, kingSide: Boolean? = null): CastlingRights {
        return when {
            kingSide == null -> when (color) {
                Color.WHITE -> copy(whiteKingSide = false, whiteQueenSide = false)
                Color.BLACK -> copy(blackKingSide = false, blackQueenSide = false)
            }
            color == Color.WHITE && kingSide -> copy(whiteKingSide = false)
            color == Color.WHITE && !kingSide -> copy(whiteQueenSide = false)
            color == Color.BLACK && kingSide -> copy(blackKingSide = false)
            else -> copy(blackQueenSide = false)
        }
    }
}

/** Observer for game events */
interface ChessObserver {
    fun onMoveMade(move: Move)
    fun onGameStateChanged(state: GameState)
    fun onCheck(color: Color)
    fun onCheckmate(winner: Color)
}
