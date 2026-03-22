package com.systemdesign.chess.approach_01_strategy_movement

import com.systemdesign.chess.common.*

/**
 * Approach 1: Strategy Pattern for Piece Movement
 * 
 * Each piece type has its own movement strategy that can be swapped
 * for variants like Chess960 or custom pieces.
 * 
 * Pattern: Strategy Pattern
 * 
 * Trade-offs:
 * + Movement rules are encapsulated and testable
 * + Easy to add new piece types or variants
 * + Piece behavior is polymorphic
 * - Need to pass board state to each strategy
 * - Strategy lookup overhead per move validation
 * 
 * When to use:
 * - When implementing chess variants with different rules
 * - When piece movement rules may change
 * - When testing movement logic in isolation
 * 
 * Extensibility:
 * - New piece type: Implement MovementStrategy interface
 * - Chess variant: Create variant-specific strategy implementations
 */

/** Movement strategy interface */
interface MovementStrategy {
    fun getValidMoves(
        piece: Piece,
        from: Position,
        board: Board
    ): List<Position>
    
    fun canCapture(
        piece: Piece,
        from: Position,
        to: Position,
        board: Board
    ): Boolean
}

/** Board representation */
class Board {
    private val squares: Array<Array<Piece?>> = Array(8) { arrayOfNulls(8) }
    
    fun getPiece(pos: Position): Piece? {
        if (!pos.isValid) return null
        return squares[pos.row][pos.col]
    }
    
    fun setPiece(pos: Position, piece: Piece?) {
        if (pos.isValid) {
            squares[pos.row][pos.col] = piece
        }
    }
    
    fun movePiece(from: Position, to: Position): Piece? {
        val piece = getPiece(from) ?: return null
        val captured = getPiece(to)
        setPiece(from, null)
        setPiece(to, piece.copy(hasMoved = true))
        return captured
    }
    
    fun findKing(color: Color): Position? {
        for (row in 0..7) {
            for (col in 0..7) {
                val pos = Position(row, col)
                val piece = getPiece(pos)
                if (piece?.type == PieceType.KING && piece.color == color) {
                    return pos
                }
            }
        }
        return null
    }
    
    fun getAllPieces(color: Color): List<Pair<Position, Piece>> {
        val pieces = mutableListOf<Pair<Position, Piece>>()
        for (row in 0..7) {
            for (col in 0..7) {
                val pos = Position(row, col)
                val piece = getPiece(pos)
                if (piece?.color == color) {
                    pieces.add(pos to piece)
                }
            }
        }
        return pieces
    }
    
    fun copy(): Board {
        val newBoard = Board()
        for (row in 0..7) {
            for (col in 0..7) {
                newBoard.squares[row][col] = squares[row][col]
            }
        }
        return newBoard
    }
    
    fun isEmpty(pos: Position): Boolean = getPiece(pos) == null
    
    fun isEnemy(pos: Position, color: Color): Boolean {
        val piece = getPiece(pos) ?: return false
        return piece.color != color
    }
    
    fun setupInitialPosition() {
        // White pieces
        squares[0][0] = StandardPiece(PieceType.ROOK, Color.WHITE)
        squares[0][1] = StandardPiece(PieceType.KNIGHT, Color.WHITE)
        squares[0][2] = StandardPiece(PieceType.BISHOP, Color.WHITE)
        squares[0][3] = StandardPiece(PieceType.QUEEN, Color.WHITE)
        squares[0][4] = StandardPiece(PieceType.KING, Color.WHITE)
        squares[0][5] = StandardPiece(PieceType.BISHOP, Color.WHITE)
        squares[0][6] = StandardPiece(PieceType.KNIGHT, Color.WHITE)
        squares[0][7] = StandardPiece(PieceType.ROOK, Color.WHITE)
        for (col in 0..7) {
            squares[1][col] = StandardPiece(PieceType.PAWN, Color.WHITE)
        }
        
        // Black pieces
        squares[7][0] = StandardPiece(PieceType.ROOK, Color.BLACK)
        squares[7][1] = StandardPiece(PieceType.KNIGHT, Color.BLACK)
        squares[7][2] = StandardPiece(PieceType.BISHOP, Color.BLACK)
        squares[7][3] = StandardPiece(PieceType.QUEEN, Color.BLACK)
        squares[7][4] = StandardPiece(PieceType.KING, Color.BLACK)
        squares[7][5] = StandardPiece(PieceType.BISHOP, Color.BLACK)
        squares[7][6] = StandardPiece(PieceType.KNIGHT, Color.BLACK)
        squares[7][7] = StandardPiece(PieceType.ROOK, Color.BLACK)
        for (col in 0..7) {
            squares[6][col] = StandardPiece(PieceType.PAWN, Color.BLACK)
        }
    }
}

/** King movement strategy */
class KingMovementStrategy : MovementStrategy {
    override fun getValidMoves(piece: Piece, from: Position, board: Board): List<Position> {
        val moves = mutableListOf<Position>()
        val directions = listOf(
            -1 to -1, -1 to 0, -1 to 1,
            0 to -1, 0 to 1,
            1 to -1, 1 to 0, 1 to 1
        )
        
        for ((dr, dc) in directions) {
            val to = Position(from.row + dr, from.col + dc)
            if (to.isValid && (board.isEmpty(to) || board.isEnemy(to, piece.color))) {
                moves.add(to)
            }
        }
        
        return moves
    }
    
    override fun canCapture(piece: Piece, from: Position, to: Position, board: Board): Boolean {
        val dr = kotlin.math.abs(from.row - to.row)
        val dc = kotlin.math.abs(from.col - to.col)
        return dr <= 1 && dc <= 1 && board.isEnemy(to, piece.color)
    }
}

/** Queen movement strategy */
class QueenMovementStrategy : MovementStrategy {
    private val rookStrategy = RookMovementStrategy()
    private val bishopStrategy = BishopMovementStrategy()
    
    override fun getValidMoves(piece: Piece, from: Position, board: Board): List<Position> {
        return rookStrategy.getValidMoves(piece, from, board) + 
               bishopStrategy.getValidMoves(piece, from, board)
    }
    
    override fun canCapture(piece: Piece, from: Position, to: Position, board: Board): Boolean {
        return rookStrategy.canCapture(piece, from, to, board) || 
               bishopStrategy.canCapture(piece, from, to, board)
    }
}

/** Rook movement strategy */
class RookMovementStrategy : MovementStrategy {
    override fun getValidMoves(piece: Piece, from: Position, board: Board): List<Position> {
        val moves = mutableListOf<Position>()
        val directions = listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0)
        
        for ((dr, dc) in directions) {
            var row = from.row + dr
            var col = from.col + dc
            while (row in 0..7 && col in 0..7) {
                val pos = Position(row, col)
                if (board.isEmpty(pos)) {
                    moves.add(pos)
                } else {
                    if (board.isEnemy(pos, piece.color)) {
                        moves.add(pos)
                    }
                    break
                }
                row += dr
                col += dc
            }
        }
        
        return moves
    }
    
    override fun canCapture(piece: Piece, from: Position, to: Position, board: Board): Boolean {
        return to in getValidMoves(piece, from, board) && board.isEnemy(to, piece.color)
    }
}

/** Bishop movement strategy */
class BishopMovementStrategy : MovementStrategy {
    override fun getValidMoves(piece: Piece, from: Position, board: Board): List<Position> {
        val moves = mutableListOf<Position>()
        val directions = listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)
        
        for ((dr, dc) in directions) {
            var row = from.row + dr
            var col = from.col + dc
            while (row in 0..7 && col in 0..7) {
                val pos = Position(row, col)
                if (board.isEmpty(pos)) {
                    moves.add(pos)
                } else {
                    if (board.isEnemy(pos, piece.color)) {
                        moves.add(pos)
                    }
                    break
                }
                row += dr
                col += dc
            }
        }
        
        return moves
    }
    
    override fun canCapture(piece: Piece, from: Position, to: Position, board: Board): Boolean {
        return to in getValidMoves(piece, from, board) && board.isEnemy(to, piece.color)
    }
}

/** Knight movement strategy */
class KnightMovementStrategy : MovementStrategy {
    override fun getValidMoves(piece: Piece, from: Position, board: Board): List<Position> {
        val moves = mutableListOf<Position>()
        val offsets = listOf(
            2 to 1, 2 to -1, -2 to 1, -2 to -1,
            1 to 2, 1 to -2, -1 to 2, -1 to -2
        )
        
        for ((dr, dc) in offsets) {
            val to = Position(from.row + dr, from.col + dc)
            if (to.isValid && (board.isEmpty(to) || board.isEnemy(to, piece.color))) {
                moves.add(to)
            }
        }
        
        return moves
    }
    
    override fun canCapture(piece: Piece, from: Position, to: Position, board: Board): Boolean {
        return to in getValidMoves(piece, from, board) && board.isEnemy(to, piece.color)
    }
}

/** Pawn movement strategy */
class PawnMovementStrategy : MovementStrategy {
    override fun getValidMoves(piece: Piece, from: Position, board: Board): List<Position> {
        val moves = mutableListOf<Position>()
        val direction = if (piece.color == Color.WHITE) 1 else -1
        val startRow = if (piece.color == Color.WHITE) 1 else 6
        
        // Forward move
        val oneStep = Position(from.row + direction, from.col)
        if (oneStep.isValid && board.isEmpty(oneStep)) {
            moves.add(oneStep)
            
            // Two-step from start
            if (from.row == startRow) {
                val twoStep = Position(from.row + 2 * direction, from.col)
                if (board.isEmpty(twoStep)) {
                    moves.add(twoStep)
                }
            }
        }
        
        // Captures
        for (dc in listOf(-1, 1)) {
            val capturePos = Position(from.row + direction, from.col + dc)
            if (capturePos.isValid && board.isEnemy(capturePos, piece.color)) {
                moves.add(capturePos)
            }
        }
        
        return moves
    }
    
    override fun canCapture(piece: Piece, from: Position, to: Position, board: Board): Boolean {
        val direction = if (piece.color == Color.WHITE) 1 else -1
        val dr = to.row - from.row
        val dc = kotlin.math.abs(to.col - from.col)
        return dr == direction && dc == 1 && board.isEnemy(to, piece.color)
    }
}

/** Strategy registry */
object MovementStrategyRegistry {
    private val strategies = mapOf(
        PieceType.KING to KingMovementStrategy(),
        PieceType.QUEEN to QueenMovementStrategy(),
        PieceType.ROOK to RookMovementStrategy(),
        PieceType.BISHOP to BishopMovementStrategy(),
        PieceType.KNIGHT to KnightMovementStrategy(),
        PieceType.PAWN to PawnMovementStrategy()
    )
    
    fun getStrategy(type: PieceType): MovementStrategy {
        return strategies[type] ?: throw IllegalArgumentException("No strategy for $type")
    }
}

/**
 * Chess game using strategy pattern for movement
 */
class StrategyChessGame {
    private val board = Board()
    private var currentTurn = Color.WHITE
    private var castlingRights = CastlingRights()
    private var enPassantTarget: Position? = null
    private var gameState = GameState.IN_PROGRESS
    private val moveHistory = mutableListOf<Move>()
    
    init {
        board.setupInitialPosition()
    }
    
    fun getCurrentTurn(): Color = currentTurn
    fun getGameState(): GameState = gameState
    fun getMoveHistory(): List<Move> = moveHistory.toList()
    
    fun getPiece(pos: Position): Piece? = board.getPiece(pos)
    
    fun getValidMoves(from: Position): List<Position> {
        val piece = board.getPiece(from) ?: return emptyList()
        if (piece.color != currentTurn) return emptyList()
        
        val strategy = MovementStrategyRegistry.getStrategy(piece.type)
        val moves = strategy.getValidMoves(piece, from, board)
        
        // Filter out moves that would leave king in check
        return moves.filter { to ->
            !wouldBeInCheck(from, to, piece.color)
        }
    }
    
    fun makeMove(from: Position, to: Position, promotionPiece: PieceType? = null): MoveResult {
        val piece = board.getPiece(from)
            ?: return MoveResult.Invalid("No piece at $from")
        
        if (piece.color != currentTurn) {
            return MoveResult.Invalid("Not ${piece.color}'s turn")
        }
        
        if (to !in getValidMoves(from)) {
            return MoveResult.IllegalMove("Invalid move for ${piece.type}")
        }
        
        val captured = board.movePiece(from, to)
        
        // Handle pawn promotion
        val isPromotion = piece.type == PieceType.PAWN && 
            (to.row == 0 || to.row == 7)
        
        if (isPromotion) {
            val newPiece = StandardPiece(
                promotionPiece ?: PieceType.QUEEN,
                piece.color,
                hasMoved = true
            )
            board.setPiece(to, newPiece)
        }
        
        // Update castling rights
        updateCastlingRights(piece, from)
        
        // Update en passant target
        enPassantTarget = if (piece.type == PieceType.PAWN && 
            kotlin.math.abs(to.row - from.row) == 2) {
            Position((from.row + to.row) / 2, from.col)
        } else null
        
        currentTurn = currentTurn.opposite()
        
        // Check game state
        val isCheck = isKingInCheck(currentTurn)
        val hasValidMoves = hasAnyValidMoves(currentTurn)
        
        gameState = when {
            isCheck && !hasValidMoves -> GameState.CHECKMATE
            !isCheck && !hasValidMoves -> GameState.STALEMATE
            isCheck -> GameState.CHECK
            else -> GameState.IN_PROGRESS
        }
        
        val move = Move(
            from = from,
            to = to,
            piece = piece,
            capturedPiece = captured,
            isPromotion = isPromotion,
            promotionPiece = if (isPromotion) (promotionPiece ?: PieceType.QUEEN) else null,
            isCheck = gameState == GameState.CHECK,
            isCheckmate = gameState == GameState.CHECKMATE
        )
        
        moveHistory.add(move)
        
        return MoveResult.Success(move, gameState)
    }
    
    private fun updateCastlingRights(piece: Piece, from: Position) {
        if (piece.type == PieceType.KING) {
            castlingRights = castlingRights.revoke(piece.color)
        } else if (piece.type == PieceType.ROOK) {
            val kingSide = from.col == 7
            castlingRights = castlingRights.revoke(piece.color, kingSide)
        }
    }
    
    private fun wouldBeInCheck(from: Position, to: Position, color: Color): Boolean {
        val testBoard = board.copy()
        testBoard.movePiece(from, to)
        return isKingInCheckOnBoard(testBoard, color)
    }
    
    private fun isKingInCheck(color: Color): Boolean {
        return isKingInCheckOnBoard(board, color)
    }
    
    private fun isKingInCheckOnBoard(testBoard: Board, color: Color): Boolean {
        val kingPos = testBoard.findKing(color) ?: return false
        val enemyColor = color.opposite()
        
        for ((pos, enemyPiece) in testBoard.getAllPieces(enemyColor)) {
            val strategy = MovementStrategyRegistry.getStrategy(enemyPiece.type)
            if (strategy.canCapture(enemyPiece, pos, kingPos, testBoard)) {
                return true
            }
        }
        
        return false
    }
    
    private fun hasAnyValidMoves(color: Color): Boolean {
        for ((pos, piece) in board.getAllPieces(color)) {
            if (getValidMovesForColor(pos, color).isNotEmpty()) {
                return true
            }
        }
        return false
    }
    
    private fun getValidMovesForColor(from: Position, color: Color): List<Position> {
        val piece = board.getPiece(from) ?: return emptyList()
        if (piece.color != color) return emptyList()
        
        val strategy = MovementStrategyRegistry.getStrategy(piece.type)
        val moves = strategy.getValidMoves(piece, from, board)
        
        return moves.filter { to ->
            !wouldBeInCheck(from, to, color)
        }
    }
}
