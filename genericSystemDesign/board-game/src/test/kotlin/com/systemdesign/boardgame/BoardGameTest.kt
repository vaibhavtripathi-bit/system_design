package com.systemdesign.boardgame

import com.systemdesign.boardgame.common.*
import com.systemdesign.boardgame.approach_01_state_machine.*
import com.systemdesign.boardgame.approach_02_strategy_rules.*
import com.systemdesign.boardgame.approach_03_command_moves.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested

class BoardGameTest {
    
    private val player1 = Player("player1", "Alice")
    private val player2 = Player("player2", "Bob")
    
    @Nested
    inner class StateMachineGameEngineTest {
        
        private lateinit var engine: StateMachineGameEngine
        
        @BeforeEach
        fun setup() {
            engine = StateMachineGameEngine()
        }
        
        @Nested
        inner class StateTransitions {
            
            @Test
            fun `starts in waiting state`() {
                assertTrue(engine.getState() is GameState.Waiting)
            }
            
            @Test
            fun `transitions to in-progress after start`() {
                engine.addPlayer(player1)
                engine.addPlayer(player2)
                engine.startGame()
                
                assertTrue(engine.getState() is GameState.InProgress)
            }
            
            @Test
            fun `cannot start without minimum players`() {
                engine.addPlayer(player1)
                
                val result = engine.startGame()
                
                assertTrue(result is GameActionResult.Failure)
                assertTrue(engine.getState() is GameState.Waiting)
            }
            
            @Test
            fun `cannot add players after game started`() {
                engine.addPlayer(player1)
                engine.addPlayer(player2)
                engine.startGame()
                
                val result = engine.addPlayer(Player("player3", "Charlie"))
                
                assertTrue(result is GameActionResult.Failure)
            }
            
            @Test
            fun `transitions to between-turns after move`() {
                setupGameWithPieces()
                
                val result = engine.makeMove(player1, Position(0, 0), Position(1, 1))
                
                assertTrue(result is MoveResult.Success)
                val newState = (result as MoveResult.Success).newState
                assertTrue(newState is GameState.BetweenTurns)
            }
            
            @Test
            fun `transitions to finished when game ends`() {
                setupGameWithPieces()
                
                val forfeitResult = engine.forfeit(player1)
                
                assertTrue(forfeitResult is GameActionResult.Success)
                val state = engine.getState()
                assertTrue(state is GameState.Finished)
                assertEquals(player2, (state as GameState.Finished).winner)
            }
        }
        
        @Nested
        inner class TurnManagement {
            
            @Test
            fun `first player starts the turn`() {
                engine.addPlayer(player1)
                engine.addPlayer(player2)
                engine.startGame()
                
                assertEquals(player1, engine.getCurrentPlayer())
            }
            
            @Test
            fun `turn rotates after end turn`() {
                setupGameWithPieces()
                
                assertEquals(player1, engine.getCurrentPlayer())
                
                engine.makeMove(player1, Position(0, 0), Position(1, 1))
                engine.endTurn()
                
                assertEquals(player2, engine.getCurrentPlayer())
            }
            
            @Test
            fun `cannot move on another players turn`() {
                setupGameWithPieces()
                
                val result = engine.makeMove(player2, Position(1, 0), Position(2, 0))
                
                assertTrue(result is MoveResult.NotYourTurn)
                assertEquals(player1, (result as MoveResult.NotYourTurn).currentPlayer)
            }
            
            @Test
            fun `turn cycles back to first player`() {
                setupGameWithPieces()
                
                engine.makeMove(player1, Position(0, 0), Position(2, 2))
                engine.endTurn()
                engine.makeMove(player2, Position(1, 0), Position(3, 0))
                engine.endTurn()
                
                assertEquals(player1, engine.getCurrentPlayer())
            }
        }
        
        @Nested
        inner class MoveValidation {
            
            @Test
            fun `rejects move with no piece at source`() {
                setupGameWithPieces()
                
                val result = engine.makeMove(player1, Position(5, 5), Position(6, 6))
                
                assertTrue(result is MoveResult.InvalidMove)
                assertTrue((result as MoveResult.InvalidMove).reason.contains("No piece"))
            }
            
            @Test
            fun `rejects move of opponent piece`() {
                setupGameWithPieces()
                
                val result = engine.makeMove(player1, Position(1, 0), Position(2, 0))
                
                assertTrue(result is MoveResult.InvalidMove)
                assertTrue((result as MoveResult.InvalidMove).reason.contains("doesn't belong"))
            }
            
            @Test
            fun `rejects move to same position`() {
                setupGameWithPieces()
                
                val result = engine.makeMove(player1, Position(0, 0), Position(0, 0))
                
                assertTrue(result is MoveResult.InvalidMove)
            }
            
            @Test
            fun `rejects move before game starts`() {
                engine.addPlayer(player1)
                engine.addPlayer(player2)
                
                val result = engine.makeMove(player1, Position(0, 0), Position(1, 0))
                
                assertTrue(result is MoveResult.GameNotStarted)
            }
        }
        
        @Nested
        inner class WinCondition {
            
            @Test
            fun `declares winner when opponent has no pieces`() {
                engine.addPlayer(player1)
                engine.addPlayer(player2)
                engine.startGame()
                
                val p1Piece = Piece("p1", "Piece", player1, Position(0, 0))
                val p2Piece = Piece("p2", "Piece", player2, Position(1, 1))
                engine.placePiece(p1Piece, Position(0, 0))
                engine.placePiece(p2Piece, Position(1, 1))
                
                val result = engine.makeMove(player1, Position(0, 0), Position(1, 1))
                
                assertTrue(result is MoveResult.Success)
                val state = (result as MoveResult.Success).newState
                assertTrue(state is GameState.Finished)
                assertEquals(player1, (state as GameState.Finished).winner)
            }
            
            @Test
            fun `declare winner manually`() {
                setupGameWithPieces()
                
                val result = engine.declareWinner(player2)
                
                assertTrue(result is GameActionResult.Success)
                val state = engine.getState()
                assertTrue(state is GameState.Finished)
                assertEquals(player2, (state as GameState.Finished).winner)
            }
        }
        
        @Nested
        inner class ObserverNotifications {
            
            @Test
            fun `notifies observers on game start`() {
                var started = false
                engine.addObserver(object : GameObserver {
                    override fun onGameStarted(players: List<Player>) { started = true }
                    override fun onTurnStarted(player: Player) {}
                    override fun onMoveMade(move: Move) {}
                    override fun onTurnEnded(player: Player) {}
                    override fun onGameEnded(winner: Player?) {}
                    override fun onPlayerJoined(player: Player) {}
                    override fun onInvalidMove(player: Player, reason: String) {}
                })
                
                engine.addPlayer(player1)
                engine.addPlayer(player2)
                engine.startGame()
                
                assertTrue(started)
            }
            
            @Test
            fun `notifies observers on move made`() {
                var moveMade: Move? = null
                engine.addObserver(object : GameObserver {
                    override fun onGameStarted(players: List<Player>) {}
                    override fun onTurnStarted(player: Player) {}
                    override fun onMoveMade(move: Move) { moveMade = move }
                    override fun onTurnEnded(player: Player) {}
                    override fun onGameEnded(winner: Player?) {}
                    override fun onPlayerJoined(player: Player) {}
                    override fun onInvalidMove(player: Player, reason: String) {}
                })
                
                setupGameWithPieces()
                engine.makeMove(player1, Position(0, 0), Position(2, 2))
                
                assertNotNull(moveMade)
                assertEquals(Position(0, 0), moveMade!!.from)
                assertEquals(Position(2, 2), moveMade!!.to)
            }
        }
        
        private fun setupGameWithPieces() {
            engine.addPlayer(player1)
            engine.addPlayer(player2)
            engine.startGame()
            
            val p1Piece = Piece("p1", "Piece", player1, Position(0, 0))
            val p2Piece = Piece("p2", "Piece", player2, Position(1, 0))
            engine.placePiece(p1Piece, Position(0, 0))
            engine.placePiece(p2Piece, Position(1, 0))
        }
    }
    
    @Nested
    inner class StrategyRulesGameEngineTest {
        
        @Nested
        inner class ChessRulesTest {
            
            private lateinit var rules: ChessRules
            private lateinit var board: Board
            
            @BeforeEach
            fun setup() {
                rules = ChessRules()
                board = Board(8, 8)
            }
            
            @Test
            fun `king moves one square in any direction`() {
                val king = Piece("k1", ChessRules.KING, player1, Position(4, 4))
                board.setPiece(Position(4, 4), king)
                
                val validMoves = listOf(
                    Position(3, 3), Position(3, 4), Position(3, 5),
                    Position(4, 3), Position(4, 5),
                    Position(5, 3), Position(5, 4), Position(5, 5)
                )
                
                validMoves.forEach { to ->
                    val result = rules.isValidMove(board, king, king.position, to)
                    assertTrue(result is MoveValidation.Valid, "King should move to $to")
                }
                
                val invalidMove = rules.isValidMove(board, king, king.position, Position(6, 6))
                assertTrue(invalidMove is MoveValidation.Invalid)
            }
            
            @Test
            fun `rook moves horizontally and vertically`() {
                val rook = Piece("r1", ChessRules.ROOK, player1, Position(4, 4))
                board.setPiece(Position(4, 4), rook)
                
                assertTrue(rules.isValidMove(board, rook, rook.position, Position(4, 0)) is MoveValidation.Valid)
                assertTrue(rules.isValidMove(board, rook, rook.position, Position(4, 7)) is MoveValidation.Valid)
                assertTrue(rules.isValidMove(board, rook, rook.position, Position(0, 4)) is MoveValidation.Valid)
                assertTrue(rules.isValidMove(board, rook, rook.position, Position(7, 4)) is MoveValidation.Valid)
                
                assertTrue(rules.isValidMove(board, rook, rook.position, Position(5, 5)) is MoveValidation.Invalid)
            }
            
            @Test
            fun `rook blocked by piece in path`() {
                val rook = Piece("r1", ChessRules.ROOK, player1, Position(4, 4))
                val blocker = Piece("b1", ChessRules.PAWN, player1, Position(4, 6))
                board.setPiece(Position(4, 4), rook)
                board.setPiece(Position(4, 6), blocker)
                
                val result = rules.isValidMove(board, rook, rook.position, Position(4, 7))
                assertTrue(result is MoveValidation.Invalid)
            }
            
            @Test
            fun `bishop moves diagonally`() {
                val bishop = Piece("b1", ChessRules.BISHOP, player1, Position(4, 4))
                board.setPiece(Position(4, 4), bishop)
                
                assertTrue(rules.isValidMove(board, bishop, bishop.position, Position(2, 2)) is MoveValidation.Valid)
                assertTrue(rules.isValidMove(board, bishop, bishop.position, Position(6, 6)) is MoveValidation.Valid)
                assertTrue(rules.isValidMove(board, bishop, bishop.position, Position(2, 6)) is MoveValidation.Valid)
                assertTrue(rules.isValidMove(board, bishop, bishop.position, Position(6, 2)) is MoveValidation.Valid)
                
                assertTrue(rules.isValidMove(board, bishop, bishop.position, Position(4, 6)) is MoveValidation.Invalid)
            }
            
            @Test
            fun `knight moves in L shape`() {
                val knight = Piece("n1", ChessRules.KNIGHT, player1, Position(4, 4))
                board.setPiece(Position(4, 4), knight)
                
                val validMoves = listOf(
                    Position(2, 3), Position(2, 5),
                    Position(3, 2), Position(3, 6),
                    Position(5, 2), Position(5, 6),
                    Position(6, 3), Position(6, 5)
                )
                
                validMoves.forEach { to ->
                    val result = rules.isValidMove(board, knight, knight.position, to)
                    assertTrue(result is MoveValidation.Valid, "Knight should move to $to")
                }
            }
            
            @Test
            fun `knight can jump over pieces`() {
                val knight = Piece("n1", ChessRules.KNIGHT, player1, Position(4, 4))
                board.setPiece(Position(4, 4), knight)
                board.setPiece(Position(4, 5), Piece("p1", ChessRules.PAWN, player1, Position(4, 5)))
                board.setPiece(Position(5, 4), Piece("p2", ChessRules.PAWN, player1, Position(5, 4)))
                
                val result = rules.isValidMove(board, knight, knight.position, Position(6, 5))
                assertTrue(result is MoveValidation.Valid)
            }
            
            @Test
            fun `pawn moves forward one square`() {
                val pawn = Piece("p1", ChessRules.PAWN, player1, Position(6, 4))
                board.setPiece(Position(6, 4), pawn)
                
                val result = rules.isValidMove(board, pawn, pawn.position, Position(5, 4))
                assertTrue(result is MoveValidation.Valid)
            }
            
            @Test
            fun `pawn can move two squares from start`() {
                val pawn = Piece("p1", ChessRules.PAWN, player1, Position(6, 4))
                board.setPiece(Position(6, 4), pawn)
                
                val result = rules.isValidMove(board, pawn, pawn.position, Position(4, 4))
                assertTrue(result is MoveValidation.Valid)
            }
            
            @Test
            fun `pawn captures diagonally`() {
                val pawn = Piece("p1", ChessRules.PAWN, player1, Position(6, 4))
                val enemyPawn = Piece("ep1", ChessRules.PAWN, player2, Position(5, 5))
                board.setPiece(Position(6, 4), pawn)
                board.setPiece(Position(5, 5), enemyPawn)
                
                val result = rules.isValidMove(board, pawn, pawn.position, Position(5, 5))
                assertTrue(result is MoveValidation.Valid)
            }
            
            @Test
            fun `queen moves like rook and bishop combined`() {
                val queen = Piece("q1", ChessRules.QUEEN, player1, Position(4, 4))
                board.setPiece(Position(4, 4), queen)
                
                assertTrue(rules.isValidMove(board, queen, queen.position, Position(4, 7)) is MoveValidation.Valid)
                assertTrue(rules.isValidMove(board, queen, queen.position, Position(7, 4)) is MoveValidation.Valid)
                assertTrue(rules.isValidMove(board, queen, queen.position, Position(7, 7)) is MoveValidation.Valid)
                assertTrue(rules.isValidMove(board, queen, queen.position, Position(1, 1)) is MoveValidation.Valid)
            }
            
            @Test
            fun `cannot capture own piece`() {
                val king = Piece("k1", ChessRules.KING, player1, Position(4, 4))
                val ownPawn = Piece("p1", ChessRules.PAWN, player1, Position(4, 5))
                board.setPiece(Position(4, 4), king)
                board.setPiece(Position(4, 5), ownPawn)
                
                val result = rules.isValidMove(board, king, king.position, Position(4, 5))
                assertTrue(result is MoveValidation.Invalid)
            }
            
            @Test
            fun `calculate score counts piece values`() {
                rules.setupBoard(board, listOf(player1, player2))
                
                val score1 = rules.calculateScore(board, player1)
                val score2 = rules.calculateScore(board, player2)
                
                assertEquals(39, score1)
                assertEquals(39, score2)
            }
            
            @Test
            fun `check win condition when king captured`() {
                val king = Piece("k1", ChessRules.KING, player1, Position(4, 4))
                board.setPiece(Position(4, 4), king)
                
                val move = Move(king, Position(3, 3), Position(4, 4))
                val result = rules.checkWinCondition(board, player1, move)
                
                assertTrue(result is WinCondition.Won)
                assertEquals(player1, (result as WinCondition.Won).winner)
            }
            
            @Test
            fun `setup board places all pieces correctly`() {
                rules.setupBoard(board, listOf(player1, player2))
                
                assertEquals(32, board.getAllPieces().size)
                assertEquals(16, board.getPiecesByPlayer(player1).size)
                assertEquals(16, board.getPiecesByPlayer(player2).size)
                
                assertNotNull(board.getPiece(Position(7, 4)))
                assertEquals(ChessRules.KING, board.getPiece(Position(7, 4))?.type)
            }
        }
        
        @Nested
        inner class CheckersRulesTest {
            
            private lateinit var rules: CheckersRules
            private lateinit var board: Board
            
            @BeforeEach
            fun setup() {
                rules = CheckersRules()
                board = Board(8, 8)
            }
            
            @Test
            fun `piece moves diagonally forward`() {
                val piece = Piece("c1", CheckersRules.PIECE, player1, Position(5, 3))
                board.setPiece(Position(5, 3), piece)
                
                assertTrue(rules.isValidMove(board, piece, piece.position, Position(4, 2)) is MoveValidation.Valid)
                assertTrue(rules.isValidMove(board, piece, piece.position, Position(4, 4)) is MoveValidation.Valid)
            }
            
            @Test
            fun `regular piece cannot move backward`() {
                val piece = Piece("c1", CheckersRules.PIECE, player1, Position(5, 3))
                board.setPiece(Position(5, 3), piece)
                
                val result = rules.isValidMove(board, piece, piece.position, Position(6, 4))
                assertTrue(result is MoveValidation.Invalid)
            }
            
            @Test
            fun `piece can jump over opponent`() {
                val piece = Piece("c1", CheckersRules.PIECE, player1, Position(5, 3))
                val enemy = Piece("e1", CheckersRules.PIECE, player2, Position(4, 4))
                board.setPiece(Position(5, 3), piece)
                board.setPiece(Position(4, 4), enemy)
                
                val result = rules.isValidMove(board, piece, piece.position, Position(3, 5))
                assertTrue(result is MoveValidation.Valid)
            }
            
            @Test
            fun `king moves in all diagonal directions`() {
                val king = Piece("k1", CheckersRules.KING, player1, Position(4, 4))
                board.setPiece(Position(4, 4), king)
                
                assertTrue(rules.isValidMove(board, king, king.position, Position(3, 3)) is MoveValidation.Valid)
                assertTrue(rules.isValidMove(board, king, king.position, Position(3, 5)) is MoveValidation.Valid)
                assertTrue(rules.isValidMove(board, king, king.position, Position(5, 3)) is MoveValidation.Valid)
                assertTrue(rules.isValidMove(board, king, king.position, Position(5, 5)) is MoveValidation.Valid)
            }
            
            @Test
            fun `cannot move to occupied square`() {
                val piece = Piece("c1", CheckersRules.PIECE, player1, Position(5, 3))
                val blocker = Piece("b1", CheckersRules.PIECE, player1, Position(4, 4))
                board.setPiece(Position(5, 3), piece)
                board.setPiece(Position(4, 4), blocker)
                
                val result = rules.isValidMove(board, piece, piece.position, Position(4, 4))
                assertTrue(result is MoveValidation.Invalid)
            }
            
            @Test
            fun `setup board places 12 pieces per player`() {
                rules.setupBoard(board, listOf(player1, player2))
                
                assertEquals(24, board.getAllPieces().size)
                assertEquals(12, board.getPiecesByPlayer(player1).size)
                assertEquals(12, board.getPiecesByPlayer(player2).size)
            }
            
            @Test
            fun `win when opponent has no pieces`() {
                val piece = Piece("c1", CheckersRules.PIECE, player1, Position(4, 4))
                board.setPiece(Position(4, 4), piece)
                
                val result = rules.checkWinCondition(board, player1, null)
                assertTrue(result is WinCondition.Won)
            }
        }
        
        @Nested
        inner class ConnectFourRulesTest {
            
            private lateinit var rules: ConnectFourRules
            private lateinit var board: Board
            
            @BeforeEach
            fun setup() {
                rules = ConnectFourRules()
                board = Board(6, 7)
            }
            
            @Test
            fun `disc must fall to lowest empty row`() {
                val disc = Piece("d1", ConnectFourRules.DISC, player1, Position(5, 3))
                
                val validResult = rules.isValidMove(board, disc, disc.position, Position(5, 3))
                assertTrue(validResult is MoveValidation.Valid)
                
                val invalidResult = rules.isValidMove(board, disc, disc.position, Position(4, 3))
                assertTrue(invalidResult is MoveValidation.Invalid)
            }
            
            @Test
            fun `get drop position returns lowest empty row`() {
                board.setPiece(Position(5, 3), Piece("d1", ConnectFourRules.DISC, player1, Position(5, 3)))
                board.setPiece(Position(4, 3), Piece("d2", ConnectFourRules.DISC, player2, Position(4, 3)))
                
                val dropPos = rules.getDropPosition(board, 3)
                assertEquals(Position(3, 3), dropPos)
            }
            
            @Test
            fun `full column returns null drop position`() {
                for (row in 0 until 6) {
                    board.setPiece(Position(row, 3), Piece("d$row", ConnectFourRules.DISC, player1, Position(row, 3)))
                }
                
                val dropPos = rules.getDropPosition(board, 3)
                assertNull(dropPos)
            }
            
            @Test
            fun `detects horizontal win`() {
                for (col in 0 until 4) {
                    board.setPiece(Position(5, col), Piece("d$col", ConnectFourRules.DISC, player1, Position(5, col)))
                }
                
                val lastMove = Move(
                    Piece("d3", ConnectFourRules.DISC, player1, Position(5, 3)),
                    Position(5, 3), Position(5, 3)
                )
                
                val result = rules.checkWinCondition(board, player1, lastMove)
                assertTrue(result is WinCondition.Won)
            }
            
            @Test
            fun `detects vertical win`() {
                for (row in 2 until 6) {
                    board.setPiece(Position(row, 3), Piece("d$row", ConnectFourRules.DISC, player1, Position(row, 3)))
                }
                
                val lastMove = Move(
                    Piece("d2", ConnectFourRules.DISC, player1, Position(2, 3)),
                    Position(2, 3), Position(2, 3)
                )
                
                val result = rules.checkWinCondition(board, player1, lastMove)
                assertTrue(result is WinCondition.Won)
            }
            
            @Test
            fun `detects diagonal win`() {
                board.setPiece(Position(5, 0), Piece("d1", ConnectFourRules.DISC, player1, Position(5, 0)))
                board.setPiece(Position(4, 1), Piece("d2", ConnectFourRules.DISC, player1, Position(4, 1)))
                board.setPiece(Position(3, 2), Piece("d3", ConnectFourRules.DISC, player1, Position(3, 2)))
                board.setPiece(Position(2, 3), Piece("d4", ConnectFourRules.DISC, player1, Position(2, 3)))
                
                val lastMove = Move(
                    Piece("d4", ConnectFourRules.DISC, player1, Position(2, 3)),
                    Position(2, 3), Position(2, 3)
                )
                
                val result = rules.checkWinCondition(board, player1, lastMove)
                assertTrue(result is WinCondition.Won)
            }
            
            @Test
            fun `detects draw when board is full`() {
                // Create a board pattern that doesn't result in any 4-in-a-row
                // Pattern: columns alternate ownership in groups of 2 to prevent horizontal/diagonal wins
                // Rows alternate to prevent vertical wins
                var pieceId = 0
                for (row in 0 until 6) {
                    for (col in 0 until 7) {
                        // Use a pattern that avoids 4-in-a-row in any direction
                        // Alternating by (col/2 + row) creates 2-wide vertical stripes with row offset
                        val owner = if ((col / 2 + row) % 2 == 0) player1 else player2
                        board.setPiece(Position(row, col), 
                            Piece("d${pieceId++}", ConnectFourRules.DISC, owner, Position(row, col)))
                    }
                }
                
                val lastMove = Move(
                    Piece("dlast", ConnectFourRules.DISC, player1, Position(0, 6)),
                    Position(0, 6), Position(0, 6)
                )
                
                val result = rules.checkWinCondition(board, player1, lastMove)
                assertTrue(result is WinCondition.Draw)
            }
        }
        
        @Nested
        inner class StrategyEngineIntegrationTest {
            
            @Test
            fun `game with chess rules`() {
                val engine = StrategyRulesGameEngine(ChessRules())
                engine.addPlayer(player1)
                engine.addPlayer(player2)
                engine.startGame()
                
                assertEquals("Chess", engine.getRules().gameName)
                assertEquals(32, engine.getBoard().getAllPieces().size)
            }
            
            @Test
            fun `game with connect four rules`() {
                val engine = StrategyRulesGameEngine(ConnectFourRules())
                engine.addPlayer(player1)
                engine.addPlayer(player2)
                engine.startGame()
                
                assertEquals("Connect Four", engine.getRules().gameName)
                assertEquals(0, engine.getBoard().getAllPieces().size)
            }
        }
    }
    
    @Nested
    inner class CommandMovesGameEngineTest {
        
        private lateinit var engine: CommandMovesGameEngine
        
        @BeforeEach
        fun setup() {
            engine = CommandMovesGameEngine()
        }
        
        @Nested
        inner class UndoRedoFunctionality {
            
            @Test
            fun `can undo a move`() {
                setupGameWithPieces()
                
                engine.makeMove(player1, Position(0, 0), Position(2, 2))
                
                assertTrue(engine.canUndo())
                val result = engine.undo()
                assertTrue(result is GameActionResult.Success)
                
                assertNotNull(engine.getBoard().getPiece(Position(0, 0)))
                assertNull(engine.getBoard().getPiece(Position(2, 2)))
            }
            
            @Test
            fun `can redo an undone move`() {
                setupGameWithPieces()
                
                engine.makeMove(player1, Position(0, 0), Position(2, 2))
                engine.undo()
                
                assertTrue(engine.canRedo())
                val result = engine.redo()
                assertTrue(result is GameActionResult.Success)
                
                assertNull(engine.getBoard().getPiece(Position(0, 0)))
                assertNotNull(engine.getBoard().getPiece(Position(2, 2)))
            }
            
            @Test
            fun `redo stack cleared after new move`() {
                setupGameWithPieces()
                
                engine.makeMove(player1, Position(0, 0), Position(2, 2))
                engine.undo()
                
                assertTrue(engine.canRedo())
                
                engine.makeMove(player1, Position(0, 0), Position(1, 1))
                
                assertFalse(engine.canRedo())
            }
            
            @Test
            fun `undo restores captured piece`() {
                engine.addPlayer(player1)
                engine.addPlayer(player2)
                engine.startGame()
                
                val p1Piece = Piece("p1", "Piece", player1, Position(0, 0))
                val p2Piece = Piece("p2", "Target", player2, Position(1, 1))
                engine.setupPiece(p1Piece, Position(0, 0))
                engine.setupPiece(p2Piece, Position(1, 1))
                
                engine.makeMove(player1, Position(0, 0), Position(1, 1))
                
                assertNull(engine.getBoard().getPiece(Position(0, 0)))
                assertEquals("Piece", engine.getBoard().getPiece(Position(1, 1))?.type)
                
                engine.undo()
                
                assertEquals("Piece", engine.getBoard().getPiece(Position(0, 0))?.type)
                assertEquals("Target", engine.getBoard().getPiece(Position(1, 1))?.type)
            }
            
            @Test
            fun `multiple undo operations`() {
                setupGameWithPieces()
                
                engine.makeMove(player1, Position(0, 0), Position(2, 2))
                engine.endTurn()
                engine.makeMove(player2, Position(1, 0), Position(3, 0))
                
                engine.undo()
                engine.undo()
                
                assertNotNull(engine.getBoard().getPiece(Position(0, 0)))
                assertNotNull(engine.getBoard().getPiece(Position(1, 0)))
            }
            
            @Test
            fun `cannot undo when nothing to undo`() {
                setupGameWithPieces()
                
                assertFalse(engine.canUndo())
                val result = engine.undo()
                assertTrue(result is GameActionResult.Failure)
            }
            
            @Test
            fun `undo disabled when config disables it`() {
                val config = GameConfig(enableUndo = false)
                engine = CommandMovesGameEngine(config)
                
                engine.addPlayer(player1)
                engine.addPlayer(player2)
                engine.startGame()
                
                val piece = Piece("p1", "Piece", player1, Position(0, 0))
                engine.setupPiece(piece, Position(0, 0))
                engine.makeMove(player1, Position(0, 0), Position(1, 1))
                
                val result = engine.undo()
                assertTrue(result is GameActionResult.Failure)
            }
        }
        
        @Nested
        inner class CommandLogging {
            
            @Test
            fun `logs executed commands`() {
                setupGameWithPieces()
                
                engine.makeMove(player1, Position(0, 0), Position(2, 2))
                
                val log = engine.getCommandLog()
                assertEquals(1, log.size)
                assertEquals(CommandAction.EXECUTE, log[0].action)
                assertEquals(player1, log[0].player)
            }
            
            @Test
            fun `logs undo operations`() {
                setupGameWithPieces()
                
                engine.makeMove(player1, Position(0, 0), Position(2, 2))
                engine.undo()
                
                val log = engine.getCommandLog()
                assertEquals(2, log.size)
                assertEquals(CommandAction.UNDO, log[1].action)
            }
            
            @Test
            fun `logs redo operations`() {
                setupGameWithPieces()
                
                engine.makeMove(player1, Position(0, 0), Position(2, 2))
                engine.undo()
                engine.redo()
                
                val log = engine.getCommandLog()
                assertEquals(3, log.size)
                assertEquals(CommandAction.REDO, log[2].action)
            }
        }
        
        @Nested
        inner class PlacePieceCommand {
            
            @Test
            fun `place piece command works`() {
                engine.addPlayer(player1)
                engine.addPlayer(player2)
                engine.startGame()
                
                val result = engine.placePiece(player1, "Disc", Position(0, 0))
                
                assertTrue(result is MoveResult.Success)
                assertNotNull(engine.getBoard().getPiece(Position(0, 0)))
            }
            
            @Test
            fun `place piece can be undone`() {
                engine.addPlayer(player1)
                engine.addPlayer(player2)
                engine.startGame()
                
                engine.placePiece(player1, "Disc", Position(0, 0))
                engine.undo()
                
                assertNull(engine.getBoard().getPiece(Position(0, 0)))
            }
            
            @Test
            fun `cannot place on occupied position`() {
                engine.addPlayer(player1)
                engine.addPlayer(player2)
                engine.startGame()
                
                // Place pieces for both players first so game doesn't end prematurely
                val r1 = engine.placePiece(player1, "Disc", Position(0, 0))
                assertTrue(r1 is MoveResult.Success, "Player1 place should succeed but got: $r1")
                
                val r2 = engine.placePiece(player2, "Disc", Position(1, 0))
                assertTrue(r2 is MoveResult.Success, "Player2 place should succeed but got: $r2")
                
                // Now player1 tries to place on player2's position - should fail
                val result = engine.placePiece(player1, "Disc", Position(1, 0))
                assertTrue(result is MoveResult.InvalidMove, "Should not be able to place on occupied position but got: $result")
            }
        }
        
        @Nested
        inner class CompositeCommandTest {
            
            @Test
            fun `composite command executes all sub-commands`() {
                val board = Board(8, 8)
                val king = Piece("k1", "King", player1, Position(7, 4))
                val rook = Piece("r1", "Rook", player1, Position(7, 7))
                board.setPiece(Position(7, 4), king)
                board.setPiece(Position(7, 7), rook)
                
                val validator = DefaultCommandValidator()
                val kingMove = PieceMoveCommand(board, player1, king, Position(7, 4), Position(7, 6), validator)
                val rookMove = PieceMoveCommand(board, player1, rook, Position(7, 7), Position(7, 5), validator)
                
                val castling = CompositeMoveCommand(
                    listOf(kingMove, rookMove),
                    player1,
                    "Kingside castling"
                )
                
                assertTrue(castling.execute())
                assertEquals(Position(7, 6), board.getPiece(Position(7, 6))?.position)
                assertEquals(Position(7, 5), board.getPiece(Position(7, 5))?.position)
            }
            
            @Test
            fun `composite command undoes all sub-commands`() {
                val board = Board(8, 8)
                val king = Piece("k1", "King", player1, Position(7, 4))
                val rook = Piece("r1", "Rook", player1, Position(7, 7))
                board.setPiece(Position(7, 4), king)
                board.setPiece(Position(7, 7), rook)
                
                val validator = DefaultCommandValidator()
                val kingMove = PieceMoveCommand(board, player1, king, Position(7, 4), Position(7, 6), validator)
                val rookMove = PieceMoveCommand(board, player1, rook, Position(7, 7), Position(7, 5), validator)
                
                val castling = CompositeMoveCommand(
                    listOf(kingMove, rookMove),
                    player1,
                    "Kingside castling"
                )
                
                castling.execute()
                castling.undo()
                
                assertNotNull(board.getPiece(Position(7, 4)))
                assertNotNull(board.getPiece(Position(7, 7)))
                assertNull(board.getPiece(Position(7, 6)))
                assertNull(board.getPiece(Position(7, 5)))
            }
        }
        
        @Nested
        inner class GameReplayerTest {
            
            @Test
            fun `replayer steps forward through moves`() {
                val board = Board(8, 8)
                val piece = Piece("p1", "Piece", player1, Position(0, 0))
                board.setPiece(Position(0, 0), piece)
                
                val validator = DefaultCommandValidator()
                val commands = listOf(
                    PieceMoveCommand(board, player1, piece, Position(0, 0), Position(1, 1), validator)
                )
                
                val replayer = GameReplayer(commands, board)
                
                assertTrue(replayer.isAtStart())
                assertTrue(replayer.stepForward())
                assertTrue(replayer.isAtEnd())
            }
            
            @Test
            fun `replayer steps backward through moves`() {
                val board = Board(8, 8)
                val piece = Piece("p1", "Piece", player1, Position(0, 0))
                board.setPiece(Position(0, 0), piece)
                
                val validator = DefaultCommandValidator()
                val commands = listOf(
                    PieceMoveCommand(board, player1, piece, Position(0, 0), Position(1, 1), validator)
                )
                
                val replayer = GameReplayer(commands, board)
                replayer.stepForward()
                
                assertTrue(replayer.stepBackward())
                assertTrue(replayer.isAtStart())
            }
            
            @Test
            fun `replayer jump to specific move`() {
                val board = Board(8, 8)
                val piece = Piece("p1", "Piece", player1, Position(0, 0))
                board.setPiece(Position(0, 0), piece)
                
                val validator = DefaultCommandValidator()
                val commands = mutableListOf<PieceMoveCommand>()
                
                var currentPiece = piece
                val positions = listOf(
                    Position(0, 0) to Position(1, 1),
                    Position(1, 1) to Position(2, 2),
                    Position(2, 2) to Position(3, 3)
                )
                
                positions.forEach { (from, to) ->
                    commands.add(PieceMoveCommand(board, player1, currentPiece, from, to, validator))
                    currentPiece = currentPiece.moveTo(to)
                }
                
                val replayer = GameReplayer(commands, board)
                
                assertTrue(replayer.jumpTo(2))
                assertEquals(2, replayer.getCurrentMoveIndex())
            }
        }
        
        private fun setupGameWithPieces() {
            engine.addPlayer(player1)
            engine.addPlayer(player2)
            engine.startGame()
            
            val p1Piece = Piece("p1", "Piece", player1, Position(0, 0))
            val p2Piece = Piece("p2", "Piece", player2, Position(1, 0))
            engine.setupPiece(p1Piece, Position(0, 0))
            engine.setupPiece(p2Piece, Position(1, 0))
        }
        
        private fun CommandMovesGameEngine.endTurn() {
            val currentState = getState()
            if (currentState is GameState.InProgress) {
                val players = getPlayers()
                val currentPlayer = currentState.currentPlayer
                val currentIndex = players.indexOf(currentPlayer)
                val nextIndex = (currentIndex + 1) % players.size
            }
        }
    }
    
    @Nested
    inner class ModelTests {
        
        @Test
        fun `board copy is independent`() {
            val original = Board(8, 8)
            val piece = Piece("p1", "Piece", player1, Position(0, 0))
            original.setPiece(Position(0, 0), piece)
            
            val copy = original.copy()
            copy.removePiece(Position(0, 0))
            
            assertNotNull(original.getPiece(Position(0, 0)))
            assertNull(copy.getPiece(Position(0, 0)))
        }
        
        @Test
        fun `position offset works correctly`() {
            val pos = Position(4, 4)
            
            assertEquals(Position(3, 5), pos.offset(-1, 1))
            assertEquals(Position(5, 3), pos.offset(1, -1))
        }
        
        @Test
        fun `piece moveTo returns new piece with updated position`() {
            val piece = Piece("p1", "Piece", player1, Position(0, 0))
            val moved = piece.moveTo(Position(1, 1))
            
            assertEquals(Position(0, 0), piece.position)
            assertEquals(Position(1, 1), moved.position)
            assertEquals(piece.id, moved.id)
        }
        
        @Test
        fun `board getPiecesByPlayer filters correctly`() {
            val board = Board(8, 8)
            board.setPiece(Position(0, 0), Piece("p1", "Piece", player1, Position(0, 0)))
            board.setPiece(Position(1, 0), Piece("p2", "Piece", player1, Position(1, 0)))
            board.setPiece(Position(2, 0), Piece("p3", "Piece", player2, Position(2, 0)))
            
            val player1Pieces = board.getPiecesByPlayer(player1)
            val player2Pieces = board.getPiecesByPlayer(player2)
            
            assertEquals(2, player1Pieces.size)
            assertEquals(1, player2Pieces.size)
        }
        
        @Test
        fun `game state toString provides meaningful output`() {
            assertTrue(GameState.Waiting.toString().contains("WAITING"))
            assertTrue(GameState.InProgress(player1).toString().contains("IN_PROGRESS"))
            assertTrue(GameState.Finished(player1).toString().contains("Winner"))
            assertTrue(GameState.Finished(null).toString().contains("Draw"))
        }
    }
}
