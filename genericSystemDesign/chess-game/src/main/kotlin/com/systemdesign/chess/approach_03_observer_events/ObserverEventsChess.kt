package com.systemdesign.chess.approach_03_observer_events

import com.systemdesign.chess.common.*
import com.systemdesign.chess.approach_01_strategy_movement.Board
import com.systemdesign.chess.approach_01_strategy_movement.MovementStrategyRegistry
import java.time.LocalDateTime

/**
 * Approach 3: Observer / Event-Driven Chess
 *
 * Game events (MoveMade, PieceCaptured, CheckDetected, GameOver, etc.) are
 * published to observers after each move. Multiple independent observers
 * react to the same event stream without coupling to each other.
 *
 * Pattern: Observer / Event Bus
 *
 * Trade-offs:
 * + Observers are fully decoupled — adding a spectator feed doesn't touch game logic
 * + Multiple cross-cutting concerns (logging, timers, analysis) compose cleanly
 * + Event stream is a natural audit trail / replay source
 * - Observer ordering is non-deterministic; side-effect sequencing is fragile
 * - Debugging requires tracing events through multiple listeners
 * - Performance cost of publishing to many observers on every move
 *
 * When to use:
 * - When multiple subsystems react to game events (UI, logging, timers, AI)
 * - When spectator / broadcast functionality is needed
 * - When post-game analysis requires a structured event log
 *
 * Extensibility:
 * - New observer: Implement GameEventObserver and register
 * - New event type: Add a GameEvent subclass and publish from the game
 */

sealed class GameEvent(val timestamp: LocalDateTime = LocalDateTime.now()) {
    data class MoveMade(
        val move: Move,
        val moveNumber: Int,
        val boardFen: String
    ) : GameEvent()

    data class PieceCaptured(
        val capturedPiece: Piece,
        val capturedAt: Position,
        val capturedBy: Piece
    ) : GameEvent()

    data class CheckDetected(
        val kingColor: Color,
        val kingPosition: Position,
        val attackingPieces: List<Pair<Position, Piece>>
    ) : GameEvent()

    data class GameOver(
        val state: GameState,
        val winner: Color?
    ) : GameEvent()

    data class CastlingPerformed(
        val color: Color,
        val isKingSide: Boolean
    ) : GameEvent()

    data class PawnPromoted(
        val position: Position,
        val color: Color,
        val promotedTo: PieceType
    ) : GameEvent()

    data class TurnChanged(
        val newTurn: Color,
        val moveNumber: Int
    ) : GameEvent()
}

interface GameEventObserver {
    fun onEvent(event: GameEvent)
}

class MoveLogger : GameEventObserver {
    private val log = mutableListOf<String>()

    override fun onEvent(event: GameEvent) {
        val entry = when (event) {
            is GameEvent.MoveMade -> {
                val prefix = if (event.move.piece.color == Color.WHITE) "${event.moveNumber}." else "${event.moveNumber}..."
                "$prefix ${event.move.toAlgebraic()}"
            }
            is GameEvent.PieceCaptured -> "  [${event.capturedBy.color} ${event.capturedBy.type} captures ${event.capturedPiece.color} ${event.capturedPiece.type} at ${event.capturedAt.toAlgebraic()}]"
            is GameEvent.CheckDetected -> "  [Check on ${event.kingColor} king at ${event.kingPosition.toAlgebraic()}]"
            is GameEvent.GameOver -> "  Result: ${event.state}" + (event.winner?.let { " — $it wins" } ?: "")
            is GameEvent.CastlingPerformed -> "  [${event.color} castles ${if (event.isKingSide) "kingside" else "queenside"}]"
            is GameEvent.PawnPromoted -> "  [Pawn promoted to ${event.promotedTo} at ${event.position.toAlgebraic()}]"
            is GameEvent.TurnChanged -> null
        }
        entry?.let { log.add(it) }
    }

    fun getLog(): List<String> = log.toList()
    fun getFormattedLog(): String = log.joinToString("\n")
}

class TimerManager(
    private val initialTimeMs: Long = 600_000,
    private val incrementMs: Long = 0
) : GameEventObserver {

    private val remainingTime = mutableMapOf(
        Color.WHITE to initialTimeMs,
        Color.BLACK to initialTimeMs
    )
    private var activeColor: Color = Color.WHITE
    private var lastMoveTimestamp: Long = System.currentTimeMillis()

    override fun onEvent(event: GameEvent) {
        when (event) {
            is GameEvent.MoveMade -> {
                val now = System.currentTimeMillis()
                val elapsed = now - lastMoveTimestamp
                remainingTime[event.move.piece.color] =
                    (remainingTime[event.move.piece.color]!! - elapsed + incrementMs).coerceAtLeast(0)
                lastMoveTimestamp = now
            }
            is GameEvent.TurnChanged -> {
                activeColor = event.newTurn
                lastMoveTimestamp = System.currentTimeMillis()
            }
            else -> {}
        }
    }

    fun getRemainingTime(color: Color): Long = remainingTime[color] ?: 0
    fun isTimeUp(color: Color): Boolean = (remainingTime[color] ?: 0) <= 0
    fun getActiveColor(): Color = activeColor
}

class SpectatorNotifier : GameEventObserver {
    data class Notification(val message: String, val timestamp: LocalDateTime)

    private val notifications = mutableListOf<Notification>()
    private val spectators = mutableListOf<(Notification) -> Unit>()

    override fun onEvent(event: GameEvent) {
        val message = when (event) {
            is GameEvent.MoveMade -> "Move ${event.moveNumber}: ${event.move.toAlgebraic()}"
            is GameEvent.PieceCaptured -> "${event.capturedBy.color} captures ${event.capturedPiece.type}!"
            is GameEvent.CheckDetected -> "Check! ${event.kingColor}'s king is under attack!"
            is GameEvent.GameOver -> when (event.state) {
                GameState.CHECKMATE -> "Checkmate! ${event.winner} wins!"
                GameState.STALEMATE -> "Stalemate! The game is a draw."
                GameState.DRAW_BY_REPETITION -> "Draw by threefold repetition."
                GameState.DRAW_BY_FIFTY_MOVE -> "Draw by fifty-move rule."
                GameState.DRAW_BY_INSUFFICIENT_MATERIAL -> "Draw by insufficient material."
                GameState.RESIGNED -> "${event.winner} wins by resignation!"
                GameState.DRAW_BY_AGREEMENT -> "Draw by agreement."
                else -> "Game over: ${event.state}"
            }
            is GameEvent.CastlingPerformed -> "${event.color} castles ${if (event.isKingSide) "kingside" else "queenside"}."
            is GameEvent.PawnPromoted -> "Pawn promoted to ${event.promotedTo}!"
            is GameEvent.TurnChanged -> null
        } ?: return

        val notification = Notification(message, event.timestamp)
        notifications.add(notification)
        spectators.forEach { it(notification) }
    }

    fun addSpectator(listener: (Notification) -> Unit) { spectators.add(listener) }
    fun getNotifications(): List<Notification> = notifications.toList()
}

class GameAnalyzer : GameEventObserver {
    private var totalMoves = 0
    private val capturesByColor = mutableMapOf(Color.WHITE to 0, Color.BLACK to 0)
    private val piecesCaptured = mutableListOf<Piece>()
    private var checksGiven = mutableMapOf(Color.WHITE to 0, Color.BLACK to 0)
    private val positionCounts = mutableMapOf<String, Int>()

    override fun onEvent(event: GameEvent) {
        when (event) {
            is GameEvent.MoveMade -> {
                totalMoves++
                positionCounts[event.boardFen] = (positionCounts[event.boardFen] ?: 0) + 1
            }
            is GameEvent.PieceCaptured -> {
                capturesByColor[event.capturedBy.color] = (capturesByColor[event.capturedBy.color] ?: 0) + 1
                piecesCaptured.add(event.capturedPiece)
            }
            is GameEvent.CheckDetected -> {
                val attacker = event.kingColor.opposite()
                checksGiven[attacker] = (checksGiven[attacker] ?: 0) + 1
            }
            else -> {}
        }
    }

    fun getTotalMoves(): Int = totalMoves
    fun getCaptureCount(color: Color): Int = capturesByColor[color] ?: 0
    fun getCapturedPieces(): List<Piece> = piecesCaptured.toList()
    fun getCheckCount(color: Color): Int = checksGiven[color] ?: 0
    fun isThreefoldRepetition(): Boolean = positionCounts.values.any { it >= 3 }

    fun getMaterialBalance(): Map<Color, Int> {
        val pieceValues = mapOf(
            PieceType.PAWN to 1, PieceType.KNIGHT to 3, PieceType.BISHOP to 3,
            PieceType.ROOK to 5, PieceType.QUEEN to 9, PieceType.KING to 0
        )
        val lost = mutableMapOf(Color.WHITE to 0, Color.BLACK to 0)
        for (piece in piecesCaptured) {
            lost[piece.color] = (lost[piece.color] ?: 0) + (pieceValues[piece.type] ?: 0)
        }
        return mapOf(
            Color.WHITE to -(lost[Color.WHITE] ?: 0),
            Color.BLACK to -(lost[Color.BLACK] ?: 0)
        )
    }
}

class EventDrivenChessGame {
    private val board = Board()
    private var currentTurn = Color.WHITE
    private var castlingRights = CastlingRights()
    private var enPassantTarget: Position? = null
    private var gameState = GameState.IN_PROGRESS
    private var moveNumber = 1
    private val observers = mutableListOf<GameEventObserver>()

    init {
        board.setupInitialPosition()
    }

    fun addObserver(observer: GameEventObserver) { observers.add(observer) }
    fun removeObserver(observer: GameEventObserver) { observers.remove(observer) }

    fun getCurrentTurn(): Color = currentTurn
    fun getGameState(): GameState = gameState
    fun getPiece(pos: Position): Piece? = board.getPiece(pos)

    fun makeMove(from: Position, to: Position, promotionPiece: PieceType? = null): MoveResult {
        val piece = board.getPiece(from)
            ?: return MoveResult.Invalid("No piece at ${from.toAlgebraic()}")

        if (piece.color != currentTurn)
            return MoveResult.Invalid("Not ${piece.color}'s turn")

        val strategy = MovementStrategyRegistry.getStrategy(piece.type)
        val validMoves = strategy.getValidMoves(piece, from, board)
            .filter { !wouldBeInCheck(from, it, piece.color) }

        if (to !in validMoves)
            return MoveResult.IllegalMove("Invalid move for ${piece.type}")

        val captured = board.getPiece(to)
        val isCastling = piece.type == PieceType.KING && kotlin.math.abs(to.col - from.col) == 2
        val isPromotion = piece.type == PieceType.PAWN && (to.row == 0 || to.row == 7)
        val isEnPassant = piece.type == PieceType.PAWN && to == enPassantTarget && captured == null

        board.movePiece(from, to)

        if (isCastling) {
            val isKingSide = to.col > from.col
            val rookFromCol = if (isKingSide) 7 else 0
            val rookToCol = if (isKingSide) 5 else 3
            board.movePiece(Position(from.row, rookFromCol), Position(from.row, rookToCol))
            castlingRights = castlingRights.revoke(piece.color)
            publish(GameEvent.CastlingPerformed(piece.color, isKingSide))
        }

        if (isEnPassant) {
            val capturedPawnPos = Position(from.row, to.col)
            val capturedPawn = board.getPiece(capturedPawnPos)
            board.setPiece(capturedPawnPos, null)
            capturedPawn?.let {
                publish(GameEvent.PieceCaptured(it, capturedPawnPos, piece))
            }
        }

        val actualPromotionType = if (isPromotion) (promotionPiece ?: PieceType.QUEEN) else null
        if (isPromotion && actualPromotionType != null) {
            board.setPiece(to, StandardPiece(actualPromotionType, piece.color, hasMoved = true))
            publish(GameEvent.PawnPromoted(to, piece.color, actualPromotionType))
        }

        if (captured != null && !isEnPassant) {
            publish(GameEvent.PieceCaptured(captured, to, piece))
        }

        if (piece.type == PieceType.KING) {
            castlingRights = castlingRights.revoke(piece.color)
        } else if (piece.type == PieceType.ROOK) {
            castlingRights = castlingRights.revoke(piece.color, from.col == 7)
        }

        enPassantTarget = if (piece.type == PieceType.PAWN && kotlin.math.abs(to.row - from.row) == 2) {
            Position((from.row + to.row) / 2, from.col)
        } else null

        val move = Move(
            from = from, to = to, piece = piece, capturedPiece = captured,
            isPromotion = isPromotion, promotionPiece = actualPromotionType,
            isCastling = isCastling, isEnPassant = isEnPassant
        )

        val fen = buildSimpleFen()
        publish(GameEvent.MoveMade(move, moveNumber, fen))

        currentTurn = currentTurn.opposite()
        publish(GameEvent.TurnChanged(currentTurn, moveNumber))
        if (piece.color == Color.BLACK) moveNumber++

        val inCheck = isKingInCheck(currentTurn)
        val hasValid = hasAnyValidMoves(currentTurn)

        gameState = when {
            inCheck && !hasValid -> GameState.CHECKMATE
            !inCheck && !hasValid -> GameState.STALEMATE
            inCheck -> GameState.CHECK
            else -> GameState.IN_PROGRESS
        }

        if (inCheck) {
            val kingPos = board.findKing(currentTurn)!!
            val attackers = findAttackers(kingPos, currentTurn.opposite())
            publish(GameEvent.CheckDetected(currentTurn, kingPos, attackers))
        }

        if (gameState == GameState.CHECKMATE || gameState == GameState.STALEMATE) {
            val winner = if (gameState == GameState.CHECKMATE) currentTurn.opposite() else null
            publish(GameEvent.GameOver(gameState, winner))
        }

        val finalMove = move.copy(
            isCheck = gameState == GameState.CHECK || gameState == GameState.CHECKMATE,
            isCheckmate = gameState == GameState.CHECKMATE
        )
        return MoveResult.Success(finalMove, gameState)
    }

    fun resign(color: Color) {
        gameState = GameState.RESIGNED
        publish(GameEvent.GameOver(GameState.RESIGNED, color.opposite()))
    }

    fun offerDraw(): Boolean {
        gameState = GameState.DRAW_BY_AGREEMENT
        publish(GameEvent.GameOver(GameState.DRAW_BY_AGREEMENT, null))
        return true
    }

    private fun publish(event: GameEvent) {
        observers.forEach { it.onEvent(event) }
    }

    private fun findAttackers(targetPos: Position, attackerColor: Color): List<Pair<Position, Piece>> {
        val attackers = mutableListOf<Pair<Position, Piece>>()
        for ((pos, piece) in board.getAllPieces(attackerColor)) {
            val strategy = MovementStrategyRegistry.getStrategy(piece.type)
            if (strategy.canCapture(piece, pos, targetPos, board)) {
                attackers.add(pos to piece)
            }
        }
        return attackers
    }

    private fun wouldBeInCheck(from: Position, to: Position, color: Color): Boolean {
        val testBoard = board.copy()
        testBoard.movePiece(from, to)
        return isKingInCheckOnBoard(testBoard, color)
    }

    private fun isKingInCheck(color: Color): Boolean = isKingInCheckOnBoard(board, color)

    private fun isKingInCheckOnBoard(b: Board, color: Color): Boolean {
        val kingPos = b.findKing(color) ?: return false
        for ((pos, piece) in b.getAllPieces(color.opposite())) {
            val strategy = MovementStrategyRegistry.getStrategy(piece.type)
            if (strategy.canCapture(piece, pos, kingPos, b)) return true
        }
        return false
    }

    private fun hasAnyValidMoves(color: Color): Boolean {
        for ((from, piece) in board.getAllPieces(color)) {
            val strategy = MovementStrategyRegistry.getStrategy(piece.type)
            val moves = strategy.getValidMoves(piece, from, board)
            if (moves.any { !wouldBeInCheck(from, it, color) }) return true
        }
        return false
    }

    private fun buildSimpleFen(): String {
        val sb = StringBuilder()
        for (row in 7 downTo 0) {
            var empty = 0
            for (col in 0..7) {
                val piece = board.getPiece(Position(row, col))
                if (piece == null) {
                    empty++
                } else {
                    if (empty > 0) { sb.append(empty); empty = 0 }
                    sb.append(piece.toString())
                }
            }
            if (empty > 0) sb.append(empty)
            if (row > 0) sb.append('/')
        }
        sb.append(if (currentTurn == Color.WHITE) " w" else " b")
        return sb.toString()
    }
}
