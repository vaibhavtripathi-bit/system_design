package com.systemdesign.expensetracker.approach_03_command_transactions

import com.systemdesign.expensetracker.common.*
import java.time.LocalDateTime
import kotlin.math.abs

/**
 * Approach 3: Command Pattern for Transaction Management
 * 
 * All expense and payment operations are encapsulated as command objects.
 * This enables full transaction history, undo/redo functionality, and
 * potential replay of operations.
 * 
 * Pattern: Command
 * 
 * Trade-offs:
 * + Full undo/redo support
 * + Complete audit trail of all operations
 * + Commands can be queued, logged, or replayed
 * + Easy to implement transaction batching
 * - Higher memory usage for command history
 * - More complex implementation
 * 
 * When to use:
 * - When undo functionality is required
 * - When operations need to be logged or audited
 * - When you need to queue operations
 * - When operations might need to be rolled back
 * 
 * Extensibility:
 * - New operation type: Implement TransactionCommand interface
 * - Batch operations: Create composite command
 */

interface TransactionCommand {
    fun execute(): CommandResult
    fun undo(): CommandResult
    val description: String
    val timestamp: LocalDateTime
}

sealed class CommandResult {
    data class Success(val message: String) : CommandResult()
    data class Failure(val message: String) : CommandResult()
}

class AddExpenseCommand(
    private val tracker: CommandableExpenseTracker,
    private val expenseDescription: String,
    private val amount: Double,
    private val paidBy: User,
    private val splits: List<Split>,
    private val category: String = "General",
    private val groupId: String? = null,
    override val timestamp: LocalDateTime = LocalDateTime.now()
) : TransactionCommand {
    
    private var createdExpense: Expense? = null
    
    override val description: String
        get() = "Add expense: '$expenseDescription' for $${String.format("%.2f", amount)}"
    
    override fun execute(): CommandResult {
        if (amount <= 0) {
            return CommandResult.Failure("Amount must be positive")
        }
        
        if (splits.isEmpty()) {
            return CommandResult.Failure("Must have at least one split")
        }
        
        val totalSplit = splits.sumOf { it.amount }
        if (abs(totalSplit - amount) > 0.01) {
            return CommandResult.Failure("Split amounts don't match expense amount")
        }
        
        val expense = Expense(
            description = expenseDescription,
            amount = amount,
            paidBy = paidBy,
            splits = splits,
            category = category,
            groupId = groupId,
            timestamp = timestamp
        )
        
        tracker.internalAddExpense(expense)
        createdExpense = expense
        
        return CommandResult.Success("Expense added: ${expense.id}")
    }
    
    override fun undo(): CommandResult {
        val expense = createdExpense ?: return CommandResult.Failure("No expense to undo")
        
        tracker.internalRemoveExpense(expense)
        createdExpense = null
        
        return CommandResult.Success("Expense undone: ${expense.description}")
    }
    
    fun getCreatedExpense(): Expense? = createdExpense
}

class RecordPaymentCommand(
    private val tracker: CommandableExpenseTracker,
    private val from: User,
    private val to: User,
    private val amount: Double,
    private val note: String = "",
    override val timestamp: LocalDateTime = LocalDateTime.now()
) : TransactionCommand {
    
    private var createdPayment: Payment? = null
    
    override val description: String
        get() = "Payment: ${from.name} pays ${to.name} $${String.format("%.2f", amount)}"
    
    override fun execute(): CommandResult {
        if (amount <= 0) {
            return CommandResult.Failure("Payment amount must be positive")
        }
        
        if (from == to) {
            return CommandResult.Failure("Cannot pay yourself")
        }
        
        val currentDebt = tracker.getDebtAmount(from, to)
        if (currentDebt <= 0) {
            return CommandResult.Failure("No debt exists from ${from.name} to ${to.name}")
        }
        
        val payment = Payment(from = from, to = to, amount = amount, note = note, timestamp = timestamp)
        
        tracker.internalRecordPayment(payment)
        createdPayment = payment
        
        return CommandResult.Success("Payment recorded: ${payment.id}")
    }
    
    override fun undo(): CommandResult {
        val payment = createdPayment ?: return CommandResult.Failure("No payment to undo")
        
        tracker.internalRemovePayment(payment)
        createdPayment = null
        
        return CommandResult.Success("Payment undone: ${from.name} to ${to.name}")
    }
    
    fun getCreatedPayment(): Payment? = createdPayment
}

class SettleDebtCommand(
    private val tracker: CommandableExpenseTracker,
    private val from: User,
    private val to: User,
    override val timestamp: LocalDateTime = LocalDateTime.now()
) : TransactionCommand {
    
    private var settledAmount: Double = 0.0
    private var createdPayment: Payment? = null
    
    override val description: String
        get() = "Settle debt: ${from.name} settles with ${to.name}"
    
    override fun execute(): CommandResult {
        if (from == to) {
            return CommandResult.Failure("Cannot settle with yourself")
        }
        
        val debtAmount = tracker.getDebtAmount(from, to)
        if (debtAmount <= 0.01) {
            return CommandResult.Failure("No debt to settle from ${from.name} to ${to.name}")
        }
        
        settledAmount = debtAmount
        val payment = Payment(
            from = from, 
            to = to, 
            amount = debtAmount, 
            note = "Settlement",
            timestamp = timestamp
        )
        
        tracker.internalRecordPayment(payment)
        createdPayment = payment
        
        return CommandResult.Success("Debt settled: ${from.name} paid ${to.name} $${String.format("%.2f", debtAmount)}")
    }
    
    override fun undo(): CommandResult {
        val payment = createdPayment ?: return CommandResult.Failure("No settlement to undo")
        
        tracker.internalRemovePayment(payment)
        createdPayment = null
        settledAmount = 0.0
        
        return CommandResult.Success("Settlement undone: ${from.name} to ${to.name}")
    }
}

class BatchCommand(
    private val commands: List<TransactionCommand>,
    private val batchDescription: String,
    override val timestamp: LocalDateTime = LocalDateTime.now()
) : TransactionCommand {
    
    private val executedCommands = mutableListOf<TransactionCommand>()
    
    override val description: String
        get() = batchDescription
    
    override fun execute(): CommandResult {
        for (command in commands) {
            when (val result = command.execute()) {
                is CommandResult.Success -> executedCommands.add(command)
                is CommandResult.Failure -> {
                    executedCommands.reversed().forEach { it.undo() }
                    executedCommands.clear()
                    return CommandResult.Failure("Batch failed at '${command.description}': ${result.message}")
                }
            }
        }
        return CommandResult.Success("Batch executed: ${executedCommands.size} commands")
    }
    
    override fun undo(): CommandResult {
        executedCommands.reversed().forEach { it.undo() }
        executedCommands.clear()
        return CommandResult.Success("Batch undone: $batchDescription")
    }
}

data class TransactionRecord(
    val command: TransactionCommand,
    val result: CommandResult,
    val executedAt: LocalDateTime = LocalDateTime.now()
)

private fun roundToTwoDecimals(value: Double): Double {
    return kotlin.math.round(value * 100) / 100
}

class CommandableExpenseTracker {
    private val expenses = mutableListOf<Expense>()
    private val payments = mutableListOf<Payment>()
    private val groups = mutableMapOf<String, Group>()
    private val userBalances = mutableMapOf<User, MutableMap<User, Double>>()
    
    private val commandHistory = mutableListOf<TransactionRecord>()
    private val undoneCommands = mutableListOf<TransactionCommand>()
    
    fun createGroup(name: String, members: List<User>): Group {
        val group = Group(name = name, members = members.toMutableList())
        groups[group.id] = group
        return group
    }
    
    fun executeCommand(command: TransactionCommand): CommandResult {
        val result = command.execute()
        
        commandHistory.add(TransactionRecord(command, result))
        
        if (result is CommandResult.Success) {
            undoneCommands.clear()
        }
        
        return result
    }
    
    fun undo(): CommandResult {
        val lastSuccessful = commandHistory.lastOrNull { it.result is CommandResult.Success }
            ?: return CommandResult.Failure("Nothing to undo")
        
        val result = lastSuccessful.command.undo()
        
        if (result is CommandResult.Success) {
            commandHistory.remove(lastSuccessful)
            undoneCommands.add(lastSuccessful.command)
        }
        
        return result
    }
    
    fun redo(): CommandResult {
        val lastUndone = undoneCommands.lastOrNull()
            ?: return CommandResult.Failure("Nothing to redo")
        
        val result = lastUndone.execute()
        
        if (result is CommandResult.Success) {
            undoneCommands.remove(lastUndone)
            commandHistory.add(TransactionRecord(lastUndone, result))
        }
        
        return result
    }
    
    fun addExpense(
        description: String,
        amount: Double,
        paidBy: User,
        splits: List<Split>,
        category: String = "General",
        groupId: String? = null
    ): CommandResult {
        val command = AddExpenseCommand(this, description, amount, paidBy, splits, category, groupId)
        return executeCommand(command)
    }
    
    fun addExpenseEqual(
        description: String,
        amount: Double,
        paidBy: User,
        participants: List<User>,
        category: String = "General",
        groupId: String? = null
    ): CommandResult {
        if (participants.isEmpty()) {
            return CommandResult.Failure("Must have at least one participant")
        }
        
        val splitAmount = amount / participants.size
        val splits = participants.mapIndexed { index, user ->
            val baseAmount = roundToTwoDecimals(splitAmount)
            Split(user, baseAmount)
        }.toMutableList()
        
        val currentTotal = splits.sumOf { it.amount }
        val remainder = amount - currentTotal
        if (abs(remainder) >= 0.01) {
            val lastSplit = splits.last()
            splits[splits.lastIndex] = lastSplit.copy(
                amount = roundToTwoDecimals(lastSplit.amount + remainder)
            )
        }
        
        return addExpense(description, amount, paidBy, splits, category, groupId)
    }
    
    fun recordPayment(from: User, to: User, amount: Double, note: String = ""): CommandResult {
        val command = RecordPaymentCommand(this, from, to, amount, note)
        return executeCommand(command)
    }
    
    fun settleDebt(from: User, to: User): CommandResult {
        val command = SettleDebtCommand(this, from, to)
        return executeCommand(command)
    }
    
    fun executeBatch(commands: List<TransactionCommand>, description: String): CommandResult {
        val batchCommand = BatchCommand(commands, description)
        return executeCommand(batchCommand)
    }
    
    internal fun internalAddExpense(expense: Expense) {
        expenses.add(expense)
        updateBalancesForExpense(expense, add = true)
    }
    
    internal fun internalRemoveExpense(expense: Expense) {
        expenses.remove(expense)
        updateBalancesForExpense(expense, add = false)
    }
    
    private fun updateBalancesForExpense(expense: Expense, add: Boolean) {
        val multiplier = if (add) 1.0 else -1.0
        
        expense.splits.forEach { split ->
            if (split.user != expense.paidBy) {
                modifyDebt(split.user, expense.paidBy, split.amount * multiplier)
            }
        }
    }
    
    private fun modifyDebt(debtor: User, creditor: User, amount: Double) {
        val debtorBalances = userBalances.getOrPut(debtor) { mutableMapOf() }
        val creditorBalances = userBalances.getOrPut(creditor) { mutableMapOf() }
        
        val existingDebtToCreditor = debtorBalances[creditor] ?: 0.0
        val existingDebtFromCreditor = creditorBalances[debtor] ?: 0.0
        
        if (amount > 0) {
            if (existingDebtFromCreditor > 0) {
                val netAmount = amount - existingDebtFromCreditor
                if (netAmount > 0) {
                    creditorBalances[debtor] = 0.0
                    debtorBalances[creditor] = existingDebtToCreditor + netAmount
                } else {
                    creditorBalances[debtor] = -netAmount
                }
            } else {
                debtorBalances[creditor] = existingDebtToCreditor + amount
            }
        } else {
            val reduceAmount = -amount
            val newDebt = existingDebtToCreditor - reduceAmount
            if (newDebt <= 0.01) {
                debtorBalances.remove(creditor)
                if (newDebt < -0.01) {
                    creditorBalances[debtor] = (creditorBalances[debtor] ?: 0.0) + (-newDebt)
                }
            } else {
                debtorBalances[creditor] = newDebt
            }
        }
    }
    
    internal fun internalRecordPayment(payment: Payment) {
        payments.add(payment)
        
        val fromBalances = userBalances.getOrPut(payment.from) { mutableMapOf() }
        val currentDebt = fromBalances[payment.to] ?: 0.0
        val newDebt = currentDebt - payment.amount
        
        if (newDebt <= 0.01) {
            fromBalances.remove(payment.to)
            if (newDebt < -0.01) {
                val toBalances = userBalances.getOrPut(payment.to) { mutableMapOf() }
                toBalances[payment.from] = (toBalances[payment.from] ?: 0.0) + (-newDebt)
            }
        } else {
            fromBalances[payment.to] = roundToTwoDecimals(newDebt)
        }
    }
    
    internal fun internalRemovePayment(payment: Payment) {
        payments.remove(payment)
        
        modifyDebt(payment.from, payment.to, payment.amount)
    }
    
    fun getDebtAmount(from: User, to: User): Double {
        return userBalances[from]?.get(to) ?: 0.0
    }
    
    fun getBalance(user: User): Balance {
        val owes = userBalances[user]?.filter { it.value > 0.01 }?.mapValues { 
            roundToTwoDecimals(it.value) 
        } ?: emptyMap()
        
        val owed = userBalances
            .filter { it.key != user }
            .mapNotNull { (otherUser, balances) ->
                val amount = balances[user] ?: 0.0
                if (amount > 0.01) otherUser to roundToTwoDecimals(amount) else null
            }
            .toMap()
        
        return Balance(user = user, owes = owes, owed = owed)
    }
    
    fun getAllBalances(): Map<User, Balance> {
        val allUsers = userBalances.keys + userBalances.values.flatMap { it.keys }
        return allUsers.distinct().associateWith { getBalance(it) }
    }
    
    fun suggestSettlements(): SettlementResult {
        val netBalances = mutableMapOf<User, Double>()
        
        userBalances.forEach { (user, debts) ->
            debts.forEach { (creditor, amount) ->
                netBalances[user] = (netBalances[user] ?: 0.0) - amount
                netBalances[creditor] = (netBalances[creditor] ?: 0.0) + amount
            }
        }
        
        val debtors = netBalances.filter { it.value < -0.01 }
            .map { it.key to -it.value }
            .sortedByDescending { it.second }
            .toMutableList()
        
        val creditors = netBalances.filter { it.value > 0.01 }
            .map { it.key to it.value }
            .sortedByDescending { it.second }
            .toMutableList()
        
        if (debtors.isEmpty() || creditors.isEmpty()) {
            return SettlementResult.NoSettlementsNeeded()
        }
        
        val settlements = mutableListOf<Settlement>()
        
        while (debtors.isNotEmpty() && creditors.isNotEmpty()) {
            val (debtor, debtAmount) = debtors.first()
            val (creditor, creditAmount) = creditors.first()
            
            val settlementAmount = minOf(debtAmount, creditAmount)
            
            if (settlementAmount > 0.01) {
                settlements.add(Settlement(from = debtor, to = creditor, amount = roundToTwoDecimals(settlementAmount)))
            }
            
            val remainingDebt = debtAmount - settlementAmount
            val remainingCredit = creditAmount - settlementAmount
            
            if (remainingDebt < 0.01) {
                debtors.removeAt(0)
            } else {
                debtors[0] = debtor to remainingDebt
            }
            
            if (remainingCredit < 0.01) {
                creditors.removeAt(0)
            } else {
                creditors[0] = creditor to remainingCredit
            }
        }
        
        return SettlementResult.Success(settlements)
    }
    
    fun getCommandHistory(): List<TransactionRecord> = commandHistory.toList()
    
    fun getSuccessfulCommands(): List<TransactionCommand> = 
        commandHistory.filter { it.result is CommandResult.Success }.map { it.command }
    
    fun canUndo(): Boolean = commandHistory.any { it.result is CommandResult.Success }
    
    fun canRedo(): Boolean = undoneCommands.isNotEmpty()
    
    fun getExpenses(): List<Expense> = expenses.toList()
    fun getPayments(): List<Payment> = payments.toList()
    fun getTotalExpenses(): Double = expenses.sumOf { it.amount }
    
    fun createAddExpenseCommand(
        description: String,
        amount: Double,
        paidBy: User,
        splits: List<Split>,
        category: String = "General",
        groupId: String? = null
    ): AddExpenseCommand {
        return AddExpenseCommand(this, description, amount, paidBy, splits, category, groupId)
    }
    
    fun createPaymentCommand(
        from: User,
        to: User,
        amount: Double,
        note: String = ""
    ): RecordPaymentCommand {
        return RecordPaymentCommand(this, from, to, amount, note)
    }
    
    fun createSettleDebtCommand(from: User, to: User): SettleDebtCommand {
        return SettleDebtCommand(this, from, to)
    }
}
