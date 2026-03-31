package com.systemdesign.chess

import com.systemdesign.chess.common.*
import com.systemdesign.chess.approach_01_strategy_movement.*
import com.systemdesign.chess.approach_02_command_memento.*
import com.systemdesign.chess.approach_03_observer_events.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested

class ChessGameTest {
    
    @Nested
    inner class PositionTest {
        
        @Test
        fun `position to algebraic notation`() {
            assertEquals("a1", Position(0, 0).toAlgebraic())
            assertEquals("e4", Position(3, 4).toAlgebraic())
            assertEquals("h8", Position(7, 7).toAlgebraic())
        }
        
        @Test
        fun `position from algebraic notation`() {
            assertEquals(Position(0, 0), Position.fromAlgebraic("a1"))
            assertEquals(Position(3, 4), Position.fromAlgebraic("e4"))
            assertEquals(Position(7, 7), Position.fromAlgebraic("h8"))
        }
        
        @Test
        fun `invalid algebraic notation returns null`() {
            assertNull(Position.fromAlgebraic("z9"))
            assertNull(Position.fromAlgebraic("a"))
            assertNull(Position.fromAlgebraic("a10"))
        }
    }
    
    @Nested
    inner class MovementStrategyTest {
        
        private lateinit var board: Board
        
        @BeforeEach
        fun setup() {
            board = Board()
        }
        
        @Test
        fun `king can move one square in any direction`() {
            val king = StandardPiece(PieceType.KING, Color.WHITE)
            board.setPiece(Position(4, 4), king)
            
            val strategy = KingMovementStrategy()
            val moves = strategy.getValidMoves(king, Position(4, 4), board)
            
            assertEquals(8, moves.size)
            assertTrue(Position(5, 5) in moves)
            assertTrue(Position(3, 3) in moves)
            assertTrue(Position(4, 5) in moves)
        }
        
        @Test
        fun `knight moves in L-shape`() {
            val knight = StandardPiece(PieceType.KNIGHT, Color.WHITE)
            board.setPiece(Position(4, 4), knight)
            
            val strategy = KnightMovementStrategy()
            val moves = strategy.getValidMoves(knight, Position(4, 4), board)
            
            assertEquals(8, moves.size)
            assertTrue(Position(6, 5) in moves)
            assertTrue(Position(6, 3) in moves)
            assertTrue(Position(2, 5) in moves)
            assertTrue(Position(2, 3) in moves)
        }
        
        @Test
        fun `rook moves in straight lines`() {
            val rook = StandardPiece(PieceType.ROOK, Color.WHITE)
            board.setPiece(Position(4, 4), rook)
            
            val strategy = RookMovementStrategy()
            val moves = strategy.getValidMoves(rook, Position(4, 4), board)
            
            // 7 squares in each direction = 14 total
            assertEquals(14, moves.size)
        }
        
        @Test
        fun `bishop moves diagonally`() {
            val bishop = StandardPiece(PieceType.BISHOP, Color.WHITE)
            board.setPiece(Position(4, 4), bishop)
            
            val strategy = BishopMovementStrategy()
            val moves = strategy.getValidMoves(bishop, Position(4, 4), board)
            
            // Should have moves in all 4 diagonal directions
            assertTrue(Position(5, 5) in moves)
            assertTrue(Position(3, 3) in moves)
            assertTrue(Position(5, 3) in moves)
            assertTrue(Position(3, 5) in moves)
        }
        
        @Test
        fun `pawn moves forward one square`() {
            val pawn = StandardPiece(PieceType.PAWN, Color.WHITE)
            board.setPiece(Position(2, 4), pawn)
            
            val strategy = PawnMovementStrategy()
            val moves = strategy.getValidMoves(pawn, Position(2, 4), board)
            
            assertTrue(Position(3, 4) in moves)
        }
        
        @Test
        fun `pawn can move two squares from start`() {
            val pawn = StandardPiece(PieceType.PAWN, Color.WHITE)
            board.setPiece(Position(1, 4), pawn)
            
            val strategy = PawnMovementStrategy()
            val moves = strategy.getValidMoves(pawn, Position(1, 4), board)
            
            assertTrue(Position(2, 4) in moves)
            assertTrue(Position(3, 4) in moves)
        }
        
        @Test
        fun `pawn captures diagonally`() {
            val pawn = StandardPiece(PieceType.PAWN, Color.WHITE)
            val enemy = StandardPiece(PieceType.PAWN, Color.BLACK)
            
            board.setPiece(Position(2, 4), pawn)
            board.setPiece(Position(3, 5), enemy)
            
            val strategy = PawnMovementStrategy()
            val moves = strategy.getValidMoves(pawn, Position(2, 4), board)
            
            assertTrue(Position(3, 5) in moves)
        }
        
        @Test
        fun `blocked rook cannot move through pieces`() {
            val rook = StandardPiece(PieceType.ROOK, Color.WHITE)
            val blocker = StandardPiece(PieceType.PAWN, Color.WHITE)
            
            board.setPiece(Position(0, 0), rook)
            board.setPiece(Position(0, 3), blocker)
            
            val strategy = RookMovementStrategy()
            val moves = strategy.getValidMoves(rook, Position(0, 0), board)
            
            assertTrue(Position(0, 2) in moves)
            assertFalse(Position(0, 4) in moves)
        }
    }
    
    @Nested
    inner class StrategyChessGameTest {
        
        private lateinit var game: StrategyChessGame
        
        @BeforeEach
        fun setup() {
            game = StrategyChessGame()
        }
        
        @Test
        fun `game starts with white's turn`() {
            assertEquals(Color.WHITE, game.getCurrentTurn())
            assertEquals(GameState.IN_PROGRESS, game.getGameState())
        }
        
        @Test
        fun `initial board setup is correct`() {
            // Check white pieces
            val whiteKing = game.getPiece(Position(0, 4))
            assertEquals(PieceType.KING, whiteKing?.type)
            assertEquals(Color.WHITE, whiteKing?.color)
            
            val whitePawn = game.getPiece(Position(1, 0))
            assertEquals(PieceType.PAWN, whitePawn?.type)
            
            // Check black pieces
            val blackKing = game.getPiece(Position(7, 4))
            assertEquals(PieceType.KING, blackKing?.type)
            assertEquals(Color.BLACK, blackKing?.color)
        }
        
        @Test
        fun `pawn can move two squares on first move`() {
            val validMoves = game.getValidMoves(Position(1, 4))
            
            assertTrue(Position(2, 4) in validMoves)
            assertTrue(Position(3, 4) in validMoves)
        }
        
        @Test
        fun `make valid move succeeds`() {
            val result = game.makeMove(Position(1, 4), Position(3, 4))
            
            assertTrue(result is MoveResult.Success)
            assertEquals(Color.BLACK, game.getCurrentTurn())
            assertNull(game.getPiece(Position(1, 4)))
            assertNotNull(game.getPiece(Position(3, 4)))
        }
        
        @Test
        fun `cannot move opponent's piece`() {
            val result = game.makeMove(Position(6, 4), Position(5, 4))
            
            assertTrue(result is MoveResult.Invalid)
            assertEquals(Color.WHITE, game.getCurrentTurn())
        }
        
        @Test
        fun `invalid move rejected`() {
            val result = game.makeMove(Position(1, 4), Position(5, 4))
            
            assertTrue(result is MoveResult.IllegalMove)
        }
        
        @Test
        fun `move alternates turns`() {
            game.makeMove(Position(1, 4), Position(3, 4))
            assertEquals(Color.BLACK, game.getCurrentTurn())
            
            game.makeMove(Position(6, 4), Position(4, 4))
            assertEquals(Color.WHITE, game.getCurrentTurn())
        }
        
        @Test
        fun `move history is recorded`() {
            game.makeMove(Position(1, 4), Position(3, 4))
            game.makeMove(Position(6, 4), Position(4, 4))
            
            val history = game.getMoveHistory()
            assertEquals(2, history.size)
        }
    }
    
    @Nested
    inner class CommandMementoGameTest {
        
        private lateinit var game: CommandMementoGame
        
        @BeforeEach
        fun setup() {
            game = CommandMementoGame()
        }
        
        @Test
        fun `can undo move`() {
            game.makeMove(Position(1, 4), Position(3, 4))
            val piece = game.getPiece(Position(3, 4))
            
            val undoneMove = game.undo()
            
            assertNotNull(undoneMove)
            assertEquals(Color.WHITE, game.getCurrentTurn())
            assertNull(game.getPiece(Position(3, 4)))
            assertNotNull(game.getPiece(Position(1, 4)))
        }
        
        @Test
        fun `can redo move`() {
            game.makeMove(Position(1, 4), Position(3, 4))
            game.undo()
            
            val redoneMove = game.redo()
            
            assertNotNull(redoneMove)
            assertEquals(Color.BLACK, game.getCurrentTurn())
            assertNotNull(game.getPiece(Position(3, 4)))
        }
        
        @Test
        fun `cannot undo when no moves made`() {
            assertFalse(game.canUndo())
            assertNull(game.undo())
        }
        
        @Test
        fun `cannot redo when at latest move`() {
            game.makeMove(Position(1, 4), Position(3, 4))
            
            assertFalse(game.canRedo())
            assertNull(game.redo())
        }
        
        @Test
        fun `multiple undos and redos work`() {
            game.makeMove(Position(1, 4), Position(3, 4)) // e4
            game.makeMove(Position(6, 4), Position(4, 4)) // e5
            game.makeMove(Position(0, 6), Position(2, 5)) // Nf3
            
            assertEquals(3, game.getMoveHistory().size)
            
            game.undo()
            game.undo()
            
            assertEquals(1, game.getMoveHistory().size)
            assertEquals(Color.BLACK, game.getCurrentTurn())
            
            game.redo()
            
            assertEquals(2, game.getMoveHistory().size)
            assertEquals(Color.WHITE, game.getCurrentTurn())
        }
        
        @Test
        fun `new move after undo clears redo history`() {
            game.makeMove(Position(1, 4), Position(3, 4))
            game.makeMove(Position(6, 4), Position(4, 4))
            
            game.undo() // Undo e5
            
            game.makeMove(Position(6, 3), Position(5, 3)) // Different move d6
            
            assertFalse(game.canRedo())
            assertEquals(2, game.getMoveHistory().size)
        }
        
        @Test
        fun `memento captures full board state`() {
            game.makeMove(Position(1, 4), Position(3, 4))
            
            val memento = game.createMemento()
            
            assertEquals(Color.BLACK, memento.currentTurn)
            assertNotNull(memento.pieces[Position(3, 4)])
        }
    }
    
    @Nested
    inner class MoveNotationTest {
        
        @Test
        fun `pawn move notation`() {
            val move = Move(
                from = Position(1, 4),
                to = Position(3, 4),
                piece = StandardPiece(PieceType.PAWN, Color.WHITE)
            )
            
            assertEquals("e4", move.toAlgebraic())
        }
        
        @Test
        fun `knight move notation`() {
            val move = Move(
                from = Position(0, 1),
                to = Position(2, 2),
                piece = StandardPiece(PieceType.KNIGHT, Color.WHITE)
            )
            
            assertEquals("Nc3", move.toAlgebraic())
        }
        
        @Test
        fun `capture notation`() {
            val move = Move(
                from = Position(3, 4),
                to = Position(4, 5),
                piece = StandardPiece(PieceType.PAWN, Color.WHITE),
                capturedPiece = StandardPiece(PieceType.PAWN, Color.BLACK)
            )
            
            assertEquals("exf5", move.toAlgebraic())
        }
        
        @Test
        fun `kingside castling notation`() {
            val move = Move(
                from = Position(0, 4),
                to = Position(0, 6),
                piece = StandardPiece(PieceType.KING, Color.WHITE),
                isCastling = true
            )
            
            assertEquals("O-O", move.toAlgebraic())
        }
        
        @Test
        fun `queenside castling notation`() {
            val move = Move(
                from = Position(0, 4),
                to = Position(0, 2),
                piece = StandardPiece(PieceType.KING, Color.WHITE),
                isCastling = true
            )
            
            assertEquals("O-O-O", move.toAlgebraic())
        }
        
        @Test
        fun `promotion notation`() {
            val move = Move(
                from = Position(6, 4),
                to = Position(7, 4),
                piece = StandardPiece(PieceType.PAWN, Color.WHITE),
                isPromotion = true,
                promotionPiece = PieceType.QUEEN
            )
            
            assertEquals("e8=Q", move.toAlgebraic())
        }
        
        @Test
        fun `check notation`() {
            val move = Move(
                from = Position(3, 5),
                to = Position(6, 5),
                piece = StandardPiece(PieceType.QUEEN, Color.WHITE),
                isCheck = true
            )
            
            assertEquals("Qf7+", move.toAlgebraic())
        }
        
        @Test
        fun `checkmate notation`() {
            val move = Move(
                from = Position(3, 5),
                to = Position(6, 5),
                piece = StandardPiece(PieceType.QUEEN, Color.WHITE),
                isCheckmate = true
            )
            
            assertEquals("Qf7#", move.toAlgebraic())
        }
    }
    
    @Nested
    inner class EventDrivenChessGameTest {
        
        private lateinit var game: EventDrivenChessGame
        private lateinit var logger: MoveLogger
        private lateinit var analyzer: GameAnalyzer
        private lateinit var spectator: SpectatorNotifier
        
        @BeforeEach
        fun setup() {
            game = EventDrivenChessGame()
            logger = MoveLogger()
            analyzer = GameAnalyzer()
            spectator = SpectatorNotifier()
            game.addObserver(logger)
            game.addObserver(analyzer)
            game.addObserver(spectator)
        }
        
        @Test
        fun `move publishes events to logger`() {
            game.makeMove(Position(1, 4), Position(3, 4))
            
            val log = logger.getLog()
            assertTrue(log.isNotEmpty())
            assertTrue(log.any { it.contains("e4") })
        }
        
        @Test
        fun `analyzer tracks total moves`() {
            game.makeMove(Position(1, 4), Position(3, 4))
            game.makeMove(Position(6, 4), Position(4, 4))
            
            assertEquals(2, analyzer.getTotalMoves())
        }
        
        @Test
        fun `capture publishes PieceCaptured event`() {
            game.makeMove(Position(1, 4), Position(3, 4))
            game.makeMove(Position(6, 3), Position(4, 3))
            game.makeMove(Position(3, 4), Position(4, 3))
            
            assertEquals(1, analyzer.getCaptureCount(Color.WHITE))
        }
        
        @Test
        fun `spectator receives notifications`() {
            game.makeMove(Position(1, 4), Position(3, 4))
            
            val notifications = spectator.getNotifications()
            assertTrue(notifications.isNotEmpty())
        }
        
        @Test
        fun `spectator listener is called`() {
            val received = mutableListOf<String>()
            spectator.addSpectator { received.add(it.message) }
            
            game.makeMove(Position(1, 4), Position(3, 4))
            
            assertTrue(received.isNotEmpty())
        }
        
        @Test
        fun `resign publishes GameOver event`() {
            game.resign(Color.BLACK)
            
            assertEquals(GameState.RESIGNED, game.getGameState())
            val notifications = spectator.getNotifications()
            assertTrue(notifications.any { it.message.contains("wins") })
        }
        
        @Test
        fun `timer manager tracks time`() {
            val timer = TimerManager(initialTimeMs = 600_000, incrementMs = 0)
            game.addObserver(timer)
            
            game.makeMove(Position(1, 4), Position(3, 4))
            
            assertTrue(timer.getRemainingTime(Color.WHITE) <= 600_000)
        }
    }
}
