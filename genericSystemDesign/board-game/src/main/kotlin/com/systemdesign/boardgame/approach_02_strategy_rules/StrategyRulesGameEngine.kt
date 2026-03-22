package com.systemdesign.boardgame.approach_02_strategy_rules

import com.systemdesign.boardgame.common.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * Approach 2: Strategy Pattern for Game Rules
 * 
 * Different game rules are encapsulated in strategy implementations.
 * The game engine delegates rule-checking to the injected strategy.
 * 
 * Pattern: Strategy Pattern
 * 
 * Trade-offs:
 * + Easy to add new game types without changing engine
 * + Rules are isolated and testable
 * + Can swap rules at runtime
 * + Each game type is self-contained
 * - Strategy interface may need to be wide to support all games
 * - Complex games may need many strategy combinations
 * 
 * When to use:
 * - When supporting multiple game types with same engine
 * - When rules need to be testable independently
 * - When game types differ primarily in rules, not flow
 * 
 * Extensibility:
 * - New game: Implement GameRulesStrategy
 * - New rule aspect: Add method to interface (careful - affects all)
 * - Game variants: Extend existing strategy classes
 */

/** Strategy interface for game rules */
interface GameRulesStrategy {
    /** Name of the game */
    val gameName: String
    
    /** Required board dimensions */
    val boardRows: Int
    val boardCols: Int
    
    /** Check if a move is valid according to game rules */
    fun isValidMove(board: Board, piece: Piece, from: Position, to: Position): MoveValidation
    
    /** Calculate score for a player */
    fun calculateScore(board: Board, player: Player): Int
    
    /** Check if a player has won */
    fun checkWinCondition(board: Board, player: Player, lastMove: Move?): WinCondition
    
    /** Get all valid moves for a piece */
    fun getValidMoves(board: Board, piece: Piece): List<Position>
    
    /** Setup initial board for this game */
    fun setupBoard(board: Board, players: List<Player>)
    
    /** Get piece types for this game */
    fun getPieceTypes(): List<String>
}

/** Result of move validation */
sealed class MoveValidation {
    data object Valid : MoveValidation()
    data class Invalid(val reason: String) : MoveValidation()
}

/** Result of win condition check */
sealed class WinCondition {
    data object NotMet : WinCondition()
    data class Won(val winner: Player) : WinCondition()
    data object Draw : WinCondition()
}

/** Chess rules implementation */
class ChessRules : GameRulesStrategy {
    override val gameName: String = "Chess"
    override val boardRows: Int = 8
    override val boardCols: Int = 8
    
    companion object {
        const val KING = "King"
        const val QUEEN = "Queen"
        const val ROOK = "Rook"
        const val BISHOP = "Bishop"
        const val KNIGHT = "Knight"
        const val PAWN = "Pawn"
    }
    
    override fun getPieceTypes(): List<String> = listOf(KING, QUEEN, ROOK, BISHOP, KNIGHT, PAWN)
    
    override fun isValidMove(board: Board, piece: Piece, from: Position, to: Position): MoveValidation {
        if (!board.isValidPosition(to)) {
            return MoveValidation.Invalid("Position $to is outside the board")
        }
        
        if (from == to) {
            return MoveValidation.Invalid("Cannot move to the same position")
        }
        
        val targetPiece = board.getPiece(to)
        if (targetPiece != null && targetPiece.owner.id == piece.owner.id) {
            return MoveValidation.Invalid("Cannot capture your own piece")
        }
        
        val rowDiff = to.row - from.row
        val colDiff = to.col - from.col
        val absRowDiff = abs(rowDiff)
        val absColDiff = abs(colDiff)
        
        val isValid = when (piece.type) {
            KING -> absRowDiff <= 1 && absColDiff <= 1
            QUEEN -> (absRowDiff == absColDiff || rowDiff == 0 || colDiff == 0) &&
                     isPathClear(board, from, to)
            ROOK -> (rowDiff == 0 || colDiff == 0) && isPathClear(board, from, to)
            BISHOP -> absRowDiff == absColDiff && isPathClear(board, from, to)
            KNIGHT -> (absRowDiff == 2 && absColDiff == 1) || (absRowDiff == 1 && absColDiff == 2)
            PAWN -> isValidPawnMove(board, piece, from, to, rowDiff, colDiff, targetPiece)
            else -> false
        }
        
        return if (isValid) MoveValidation.Valid 
               else MoveValidation.Invalid("Invalid ${piece.type} move")
    }
    
    private fun isValidPawnMove(
        board: Board,
        piece: Piece,
        from: Position,
        to: Position,
        rowDiff: Int,
        colDiff: Int,
        targetPiece: Piece?
    ): Boolean {
        val direction = if (piece.owner.id == "player1") -1 else 1
        val startRow = if (piece.owner.id == "player1") 6 else 1
        
        if (colDiff == 0 && targetPiece == null) {
            if (rowDiff == direction) return true
            if (rowDiff == 2 * direction && from.row == startRow && 
                board.isEmpty(Position(from.row + direction, from.col))) {
                return true
            }
        }
        
        if (abs(colDiff) == 1 && rowDiff == direction && targetPiece != null) {
            return true
        }
        
        return false
    }
    
    private fun isPathClear(board: Board, from: Position, to: Position): Boolean {
        val rowStep = (to.row - from.row).coerceIn(-1, 1)
        val colStep = (to.col - from.col).coerceIn(-1, 1)
        
        var current = Position(from.row + rowStep, from.col + colStep)
        while (current != to) {
            if (board.isOccupied(current)) return false
            current = Position(current.row + rowStep, current.col + colStep)
        }
        return true
    }
    
    override fun calculateScore(board: Board, player: Player): Int {
        val pieceValues = mapOf(
            KING to 0,
            QUEEN to 9,
            ROOK to 5,
            BISHOP to 3,
            KNIGHT to 3,
            PAWN to 1
        )
        return board.getPiecesByPlayer(player).sumOf { pieceValues[it.type] ?: 0 }
    }
    
    override fun checkWinCondition(board: Board, player: Player, lastMove: Move?): WinCondition {
        val opponent = board.getAllPieces().find { it.owner.id != player.id }?.owner
            ?: return WinCondition.Won(player)
        
        val opponentKing = board.getPiecesByPlayer(opponent).find { it.type == KING }
        if (opponentKing == null) {
            return WinCondition.Won(player)
        }
        
        if (board.getAllPieces().size == 2 && 
            board.getAllPieces().all { it.type == KING }) {
            return WinCondition.Draw
        }
        
        return WinCondition.NotMet
    }
    
    override fun getValidMoves(board: Board, piece: Piece): List<Position> {
        val validMoves = mutableListOf<Position>()
        for (row in 0 until board.rows) {
            for (col in 0 until board.cols) {
                val to = Position(row, col)
                if (isValidMove(board, piece, piece.position, to) == MoveValidation.Valid) {
                    validMoves.add(to)
                }
            }
        }
        return validMoves
    }
    
    override fun setupBoard(board: Board, players: List<Player>) {
        require(players.size == 2) { "Chess requires exactly 2 players" }
        board.clear()
        
        val player1 = players[0]
        val player2 = players[1]
        
        val backRow = listOf(ROOK, KNIGHT, BISHOP, QUEEN, KING, BISHOP, KNIGHT, ROOK)
        
        backRow.forEachIndexed { col, type ->
            board.setPiece(Position(7, col), Piece("w$type$col", type, player1, Position(7, col)))
            board.setPiece(Position(0, col), Piece("b$type$col", type, player2, Position(0, col)))
        }
        
        for (col in 0 until 8) {
            board.setPiece(Position(6, col), Piece("wPawn$col", PAWN, player1, Position(6, col)))
            board.setPiece(Position(1, col), Piece("bPawn$col", PAWN, player2, Position(1, col)))
        }
    }
}

/** Checkers rules implementation */
class CheckersRules : GameRulesStrategy {
    override val gameName: String = "Checkers"
    override val boardRows: Int = 8
    override val boardCols: Int = 8
    
    companion object {
        const val PIECE = "Piece"
        const val KING = "King"
    }
    
    override fun getPieceTypes(): List<String> = listOf(PIECE, KING)
    
    override fun isValidMove(board: Board, piece: Piece, from: Position, to: Position): MoveValidation {
        if (!board.isValidPosition(to)) {
            return MoveValidation.Invalid("Position $to is outside the board")
        }
        
        if (board.isOccupied(to)) {
            return MoveValidation.Invalid("Position $to is already occupied")
        }
        
        val rowDiff = to.row - from.row
        val colDiff = abs(to.col - from.col)
        
        val forwardDirection = if (piece.owner.id == "player1") -1 else 1
        val canMoveBackward = piece.type == KING
        
        if (colDiff == 1 && (rowDiff == forwardDirection || (canMoveBackward && rowDiff == -forwardDirection))) {
            return MoveValidation.Valid
        }
        
        if (colDiff == 2 && (abs(rowDiff) == 2)) {
            if (!canMoveBackward && rowDiff != 2 * forwardDirection) {
                return MoveValidation.Invalid("Regular pieces cannot capture backward")
            }
            
            val jumpedRow = from.row + rowDiff / 2
            val jumpedCol = from.col + (to.col - from.col) / 2
            val jumpedPiece = board.getPiece(Position(jumpedRow, jumpedCol))
            
            if (jumpedPiece != null && jumpedPiece.owner.id != piece.owner.id) {
                return MoveValidation.Valid
            }
            return MoveValidation.Invalid("Must jump over an opponent's piece")
        }
        
        return MoveValidation.Invalid("Invalid checkers move")
    }
    
    override fun calculateScore(board: Board, player: Player): Int {
        return board.getPiecesByPlayer(player).fold(0) { acc, piece -> 
            acc + if (piece.type == KING) 2 else 1 
        }
    }
    
    override fun checkWinCondition(board: Board, player: Player, lastMove: Move?): WinCondition {
        val opponents = board.getAllPieces().filter { it.owner.id != player.id }
        if (opponents.isEmpty()) {
            return WinCondition.Won(player)
        }
        
        val opponentOwner = opponents.first().owner
        val hasValidMoves = opponents.any { piece ->
            getValidMoves(board, piece).isNotEmpty()
        }
        
        if (!hasValidMoves) {
            return WinCondition.Won(player)
        }
        
        return WinCondition.NotMet
    }
    
    override fun getValidMoves(board: Board, piece: Piece): List<Position> {
        val validMoves = mutableListOf<Position>()
        val directions = if (piece.type == KING) {
            listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)
        } else {
            val forward = if (piece.owner.id == "player1") -1 else 1
            listOf(forward to -1, forward to 1)
        }
        
        for ((rowDir, colDir) in directions) {
            val simpleMove = Position(piece.position.row + rowDir, piece.position.col + colDir)
            if (isValidMove(board, piece, piece.position, simpleMove) == MoveValidation.Valid) {
                validMoves.add(simpleMove)
            }
            
            val jumpMove = Position(piece.position.row + 2 * rowDir, piece.position.col + 2 * colDir)
            if (isValidMove(board, piece, piece.position, jumpMove) == MoveValidation.Valid) {
                validMoves.add(jumpMove)
            }
        }
        
        return validMoves
    }
    
    override fun setupBoard(board: Board, players: List<Player>) {
        require(players.size == 2) { "Checkers requires exactly 2 players" }
        board.clear()
        
        val player1 = players[0]
        val player2 = players[1]
        
        for (row in 0 until 3) {
            for (col in 0 until 8) {
                if ((row + col) % 2 == 1) {
                    board.setPiece(
                        Position(row, col),
                        Piece("b${row}${col}", PIECE, player2, Position(row, col))
                    )
                }
            }
        }
        
        for (row in 5 until 8) {
            for (col in 0 until 8) {
                if ((row + col) % 2 == 1) {
                    board.setPiece(
                        Position(row, col),
                        Piece("w${row}${col}", PIECE, player1, Position(row, col))
                    )
                }
            }
        }
    }
}

/** Connect Four rules implementation */
class ConnectFourRules : GameRulesStrategy {
    override val gameName: String = "Connect Four"
    override val boardRows: Int = 6
    override val boardCols: Int = 7
    
    companion object {
        const val DISC = "Disc"
    }
    
    override fun getPieceTypes(): List<String> = listOf(DISC)
    
    override fun isValidMove(board: Board, piece: Piece, from: Position, to: Position): MoveValidation {
        if (to.col !in 0 until board.cols) {
            return MoveValidation.Invalid("Column ${to.col} is outside the board")
        }
        
        val bottomRow = findLowestEmptyRow(board, to.col)
        if (bottomRow < 0) {
            return MoveValidation.Invalid("Column ${to.col} is full")
        }
        
        if (to.row != bottomRow) {
            return MoveValidation.Invalid("Disc must fall to row $bottomRow")
        }
        
        return MoveValidation.Valid
    }
    
    private fun findLowestEmptyRow(board: Board, col: Int): Int {
        for (row in board.rows - 1 downTo 0) {
            if (board.isEmpty(Position(row, col))) {
                return row
            }
        }
        return -1
    }
    
    fun getDropPosition(board: Board, col: Int): Position? {
        val row = findLowestEmptyRow(board, col)
        return if (row >= 0) Position(row, col) else null
    }
    
    override fun calculateScore(board: Board, player: Player): Int {
        return board.getPiecesByPlayer(player).size
    }
    
    override fun checkWinCondition(board: Board, player: Player, lastMove: Move?): WinCondition {
        lastMove ?: return WinCondition.NotMet
        
        val pos = lastMove.to
        if (checkDirection(board, player, pos, 0, 1) ||
            checkDirection(board, player, pos, 1, 0) ||
            checkDirection(board, player, pos, 1, 1) ||
            checkDirection(board, player, pos, 1, -1)) {
            return WinCondition.Won(player)
        }
        
        val isBoardFull = (0 until board.cols).all { col ->
            board.isOccupied(Position(0, col))
        }
        if (isBoardFull) {
            return WinCondition.Draw
        }
        
        return WinCondition.NotMet
    }
    
    private fun checkDirection(
        board: Board, 
        player: Player, 
        start: Position, 
        rowDir: Int, 
        colDir: Int
    ): Boolean {
        var count = 1
        
        var row = start.row + rowDir
        var col = start.col + colDir
        while (row in 0 until board.rows && col in 0 until board.cols) {
            val piece = board.getPiece(Position(row, col))
            if (piece?.owner?.id == player.id) {
                count++
                row += rowDir
                col += colDir
            } else break
        }
        
        row = start.row - rowDir
        col = start.col - colDir
        while (row in 0 until board.rows && col in 0 until board.cols) {
            val piece = board.getPiece(Position(row, col))
            if (piece?.owner?.id == player.id) {
                count++
                row -= rowDir
                col -= colDir
            } else break
        }
        
        return count >= 4
    }
    
    override fun getValidMoves(board: Board, piece: Piece): List<Position> {
        return (0 until board.cols).mapNotNull { col ->
            getDropPosition(board, col)
        }
    }
    
    override fun setupBoard(board: Board, players: List<Player>) {
        board.clear()
    }
}

/** Strategy-based game engine */
class StrategyRulesGameEngine(
    private val rules: GameRulesStrategy,
    private val config: GameConfig = GameConfig()
) {
    private var state: GameState = GameState.Waiting
    private val players = mutableListOf<Player>()
    private var currentPlayerIndex = 0
    private var board = Board(rules.boardRows, rules.boardCols)
    private val moveHistory = mutableListOf<Move>()
    private val observers = CopyOnWriteArrayList<GameObserver>()
    
    fun getState(): GameState = state
    fun getPlayers(): List<Player> = players.toList()
    fun getCurrentPlayer(): Player = players[currentPlayerIndex]
    fun getBoard(): Board = board.copy()
    fun getMoveHistory(): List<Move> = moveHistory.toList()
    fun getRules(): GameRulesStrategy = rules
    
    fun addPlayer(player: Player): GameActionResult {
        if (state !is GameState.Waiting) {
            return GameActionResult.Failure("Cannot add players after game has started")
        }
        
        if (players.size >= config.maxPlayers) {
            return GameActionResult.Failure("Maximum players (${config.maxPlayers}) already joined")
        }
        
        players.add(player)
        observers.forEach { it.onPlayerJoined(player) }
        return GameActionResult.Success("${player.name} joined the game")
    }
    
    fun startGame(): GameActionResult {
        if (state !is GameState.Waiting) {
            return GameActionResult.Failure("Game already started")
        }
        
        if (players.size < config.minPlayers) {
            return GameActionResult.Failure("Need at least ${config.minPlayers} players")
        }
        
        rules.setupBoard(board, players)
        state = GameState.InProgress(players[currentPlayerIndex])
        observers.forEach { it.onGameStarted(players) }
        observers.forEach { it.onTurnStarted(getCurrentPlayer()) }
        
        return GameActionResult.Success("${rules.gameName} started!")
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
                
                when (val validation = rules.isValidMove(board, piece, from, to)) {
                    is MoveValidation.Valid -> {
                        val capturedPiece = board.getPiece(to)
                        val move = Move(piece, from, to, capturedPiece = capturedPiece)
                        
                        executeMove(from, to)
                        moveHistory.add(move)
                        observers.forEach { it.onMoveMade(move) }
                        
                        when (val winCondition = rules.checkWinCondition(board, player, move)) {
                            is WinCondition.Won -> {
                                state = GameState.Finished(winCondition.winner)
                                observers.forEach { it.onGameEnded(winCondition.winner) }
                                MoveResult.Success(move, state)
                            }
                            is WinCondition.Draw -> {
                                state = GameState.Finished(null)
                                observers.forEach { it.onGameEnded(null) }
                                MoveResult.Success(move, state)
                            }
                            is WinCondition.NotMet -> {
                                advanceTurn()
                                MoveResult.Success(move, state)
                            }
                        }
                    }
                    is MoveValidation.Invalid -> {
                        observers.forEach { it.onInvalidMove(player, validation.reason) }
                        MoveResult.InvalidMove(validation.reason)
                    }
                }
            }
        }
    }
    
    private fun executeMove(from: Position, to: Position) {
        val piece = board.removePiece(from) ?: return
        board.removePiece(to)
        board.setPiece(to, piece.moveTo(to))
    }
    
    private fun advanceTurn() {
        observers.forEach { it.onTurnEnded(getCurrentPlayer()) }
        currentPlayerIndex = (currentPlayerIndex + 1) % players.size
        state = GameState.InProgress(getCurrentPlayer())
        observers.forEach { it.onTurnStarted(getCurrentPlayer()) }
    }
    
    fun getValidMoves(piece: Piece): List<Position> = rules.getValidMoves(board, piece)
    
    fun getScore(player: Player): Int = rules.calculateScore(board, player)
    
    fun placePiece(player: Player, position: Position): GameActionResult {
        val piece = Piece(
            id = "${player.id}_${System.currentTimeMillis()}",
            type = rules.getPieceTypes().first(),
            owner = player,
            position = position
        )
        
        if (!board.isValidPosition(position)) {
            return GameActionResult.Failure("Invalid position")
        }
        
        if (board.isOccupied(position)) {
            return GameActionResult.Failure("Position occupied")
        }
        
        board.setPiece(position, piece)
        return GameActionResult.Success("Piece placed at $position")
    }
    
    fun addObserver(observer: GameObserver) {
        observers.add(observer)
    }
    
    fun removeObserver(observer: GameObserver) {
        observers.remove(observer)
    }
}
