package com.systemdesign.expensetracker.approach_01_strategy_split

import com.systemdesign.expensetracker.common.*
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.floor

/**
 * Approach 1: Strategy Pattern for Split Methods
 * 
 * Different splitting strategies are encapsulated as interchangeable algorithms.
 * The expense tracker can use any split strategy without knowing its implementation.
 * 
 * Pattern: Strategy
 * 
 * Trade-offs:
 * + Easy to add new split methods without modifying existing code
 * + Each strategy is independently testable
 * + Runtime strategy selection
 * - More classes for simple splitting logic
 * - Strategy must be selected before expense creation
 * 
 * When to use:
 * - When multiple algorithms exist for a task (different split methods)
 * - When algorithm selection should be configurable
 * - When algorithms need to be swapped at runtime
 * 
 * Extensibility:
 * - New split method: Implement SplitStrategy interface
 * - Custom rounding: Override remainder handling in strategy
 */

interface SplitStrategy {
    fun split(amount: Double, participants: List<User>): List<Split>
    fun split(amount: Double, participants: List<User>, metadata: Map<User, Double>): List<Split>
    val splitType: SplitType
}

class EqualSplitStrategy : SplitStrategy {
    override val splitType = SplitType.EQUAL
    
    override fun split(amount: Double, participants: List<User>): List<Split> {
        require(participants.isNotEmpty()) { "Must have at least one participant" }
        
        val count = participants.size
        val baseAmount = floor(amount * 100 / count) / 100
        val remainder = amount - (baseAmount * count)
        val remainderCents = (remainder * 100).toInt()
        
        return participants.mapIndexed { index, user ->
            val extra = if (index < remainderCents) 0.01 else 0.0
            Split(user, roundToTwoDecimals(baseAmount + extra))
        }
    }
    
    override fun split(amount: Double, participants: List<User>, metadata: Map<User, Double>): List<Split> {
        return split(amount, participants)
    }
}

class ExactAmountStrategy : SplitStrategy {
    override val splitType = SplitType.EXACT
    
    override fun split(amount: Double, participants: List<User>): List<Split> {
        throw IllegalArgumentException("ExactAmountStrategy requires exact amounts in metadata")
    }
    
    override fun split(amount: Double, participants: List<User>, metadata: Map<User, Double>): List<Split> {
        require(participants.isNotEmpty()) { "Must have at least one participant" }
        require(metadata.isNotEmpty()) { "Must provide exact amounts for each participant" }
        
        val totalSpecified = metadata.values.sum()
        require(abs(totalSpecified - amount) < 0.01) { 
            "Sum of exact amounts ($totalSpecified) must equal total amount ($amount)" 
        }
        
        return participants.mapNotNull { user ->
            metadata[user]?.let { userAmount ->
                Split(user, roundToTwoDecimals(userAmount))
            }
        }
    }
}

class PercentageStrategy : SplitStrategy {
    override val splitType = SplitType.PERCENTAGE
    
    override fun split(amount: Double, participants: List<User>): List<Split> {
        throw IllegalArgumentException("PercentageStrategy requires percentages in metadata")
    }
    
    override fun split(amount: Double, participants: List<User>, metadata: Map<User, Double>): List<Split> {
        require(participants.isNotEmpty()) { "Must have at least one participant" }
        require(metadata.isNotEmpty()) { "Must provide percentage for each participant" }
        
        val totalPercentage = metadata.values.sum()
        require(abs(totalPercentage - 100.0) < 0.01) { 
            "Percentages must sum to 100, got $totalPercentage" 
        }
        
        val splits = mutableListOf<Split>()
        var totalAssigned = 0.0
        
        val sortedParticipants = participants.filter { metadata.containsKey(it) }
        
        sortedParticipants.dropLast(1).forEach { user ->
            val percentage = metadata[user] ?: 0.0
            val userAmount = roundToTwoDecimals(amount * percentage / 100)
            splits.add(Split(user, userAmount))
            totalAssigned += userAmount
        }
        
        sortedParticipants.lastOrNull()?.let { lastUser ->
            val lastAmount = roundToTwoDecimals(amount - totalAssigned)
            splits.add(Split(lastUser, lastAmount))
        }
        
        return splits
    }
}

class ShareBasedStrategy : SplitStrategy {
    override val splitType = SplitType.SHARE_BASED
    
    override fun split(amount: Double, participants: List<User>): List<Split> {
        throw IllegalArgumentException("ShareBasedStrategy requires shares in metadata")
    }
    
    override fun split(amount: Double, participants: List<User>, metadata: Map<User, Double>): List<Split> {
        require(participants.isNotEmpty()) { "Must have at least one participant" }
        require(metadata.isNotEmpty()) { "Must provide share count for each participant" }
        require(metadata.values.all { it > 0 }) { "All shares must be positive" }
        
        val totalShares = metadata.values.sum()
        val amountPerShare = amount / totalShares
        
        val splits = mutableListOf<Split>()
        var totalAssigned = 0.0
        
        val sortedParticipants = participants.filter { metadata.containsKey(it) }
        
        sortedParticipants.dropLast(1).forEach { user ->
            val shares = metadata[user] ?: 0.0
            val userAmount = roundToTwoDecimals(amountPerShare * shares)
            splits.add(Split(user, userAmount))
            totalAssigned += userAmount
        }
        
        sortedParticipants.lastOrNull()?.let { lastUser ->
            val lastAmount = roundToTwoDecimals(amount - totalAssigned)
            splits.add(Split(lastUser, lastAmount))
        }
        
        return splits
    }
}

private fun roundToTwoDecimals(value: Double): Double {
    return kotlin.math.round(value * 100) / 100
}

class StrategySplitExpenseTracker {
    private val expenses = mutableListOf<Expense>()
    private val payments = mutableListOf<Payment>()
    private val groups = mutableMapOf<String, Group>()
    private val userBalances = mutableMapOf<User, MutableMap<User, Double>>()
    
    private var defaultStrategy: SplitStrategy = EqualSplitStrategy()
    
    fun setDefaultStrategy(strategy: SplitStrategy) {
        defaultStrategy = strategy
    }
    
    fun createGroup(name: String, members: List<User>): Group {
        val group = Group(name = name, members = members.toMutableList())
        groups[group.id] = group
        return group
    }
    
    fun addExpenseEqual(
        description: String,
        amount: Double,
        paidBy: User,
        participants: List<User>,
        category: String = "General",
        groupId: String? = null
    ): ExpenseResult {
        val strategy = EqualSplitStrategy()
        val splits = strategy.split(amount, participants)
        return createExpense(description, amount, paidBy, splits, category, groupId)
    }
    
    fun addExpenseExact(
        description: String,
        amount: Double,
        paidBy: User,
        exactAmounts: Map<User, Double>,
        category: String = "General",
        groupId: String? = null
    ): ExpenseResult {
        val strategy = ExactAmountStrategy()
        return try {
            val splits = strategy.split(amount, exactAmounts.keys.toList(), exactAmounts)
            createExpense(description, amount, paidBy, splits, category, groupId)
        } catch (e: IllegalArgumentException) {
            ExpenseResult.ValidationError(e.message ?: "Invalid exact amounts")
        }
    }
    
    fun addExpensePercentage(
        description: String,
        amount: Double,
        paidBy: User,
        percentages: Map<User, Double>,
        category: String = "General",
        groupId: String? = null
    ): ExpenseResult {
        val strategy = PercentageStrategy()
        return try {
            val splits = strategy.split(amount, percentages.keys.toList(), percentages)
            createExpense(description, amount, paidBy, splits, category, groupId)
        } catch (e: IllegalArgumentException) {
            ExpenseResult.ValidationError(e.message ?: "Invalid percentages")
        }
    }
    
    fun addExpenseByShares(
        description: String,
        amount: Double,
        paidBy: User,
        shares: Map<User, Double>,
        category: String = "General",
        groupId: String? = null
    ): ExpenseResult {
        val strategy = ShareBasedStrategy()
        return try {
            val splits = strategy.split(amount, shares.keys.toList(), shares)
            createExpense(description, amount, paidBy, splits, category, groupId)
        } catch (e: IllegalArgumentException) {
            ExpenseResult.ValidationError(e.message ?: "Invalid shares")
        }
    }
    
    fun addExpenseWithStrategy(
        description: String,
        amount: Double,
        paidBy: User,
        participants: List<User>,
        strategy: SplitStrategy,
        metadata: Map<User, Double> = emptyMap(),
        category: String = "General",
        groupId: String? = null
    ): ExpenseResult {
        return try {
            val splits = if (metadata.isEmpty()) {
                strategy.split(amount, participants)
            } else {
                strategy.split(amount, participants, metadata)
            }
            createExpense(description, amount, paidBy, splits, category, groupId)
        } catch (e: IllegalArgumentException) {
            ExpenseResult.ValidationError(e.message ?: "Invalid split configuration")
        }
    }
    
    private fun createExpense(
        description: String,
        amount: Double,
        paidBy: User,
        splits: List<Split>,
        category: String,
        groupId: String?
    ): ExpenseResult {
        val expense = Expense(
            description = description,
            amount = amount,
            paidBy = paidBy,
            splits = splits,
            category = category,
            groupId = groupId
        )
        
        if (!expense.validateSplits()) {
            return ExpenseResult.ValidationError("Split amounts don't sum to total expense")
        }
        
        expenses.add(expense)
        updateBalances(expense)
        
        return ExpenseResult.Success(expense)
    }
    
    private fun updateBalances(expense: Expense) {
        expense.splits.forEach { split ->
            if (split.user != expense.paidBy) {
                addDebt(split.user, expense.paidBy, split.amount)
            }
        }
    }
    
    private fun addDebt(debtor: User, creditor: User, amount: Double) {
        val debtorBalances = userBalances.getOrPut(debtor) { mutableMapOf() }
        val creditorBalances = userBalances.getOrPut(creditor) { mutableMapOf() }
        
        val existingDebtToCreditor = debtorBalances[creditor] ?: 0.0
        val existingDebtFromCreditor = creditorBalances[debtor] ?: 0.0
        
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
    }
    
    fun recordPayment(from: User, to: User, amount: Double, note: String = ""): PaymentResult {
        if (amount <= 0) {
            return PaymentResult.ValidationError("Payment amount must be positive")
        }
        
        if (from == to) {
            return PaymentResult.ValidationError("Cannot pay yourself")
        }
        
        val fromBalances = userBalances[from] ?: mutableMapOf()
        val currentDebt = fromBalances[to] ?: 0.0
        
        if (currentDebt <= 0) {
            return PaymentResult.NoDebtExists(from, to)
        }
        
        val payment = Payment(from = from, to = to, amount = amount, note = note)
        payments.add(payment)
        
        val newDebt = currentDebt - amount
        if (newDebt <= 0.01) {
            fromBalances.remove(to)
            if (newDebt < -0.01) {
                val toBalances = userBalances.getOrPut(to) { mutableMapOf() }
                toBalances[from] = -newDebt
            }
        } else {
            fromBalances[to] = roundToTwoDecimals(newDebt)
        }
        
        return PaymentResult.Success(payment)
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
    
    fun getExpenses(): List<Expense> = expenses.toList()
    
    fun getExpensesByGroup(groupId: String): List<Expense> = 
        expenses.filter { it.groupId == groupId }
    
    fun getExpensesByUser(user: User): List<Expense> = 
        expenses.filter { it.paidBy == user || it.splits.any { split -> split.user == user } }
    
    fun getExpensesByCategory(category: String): List<Expense> = 
        expenses.filter { it.category == category }
    
    fun getPayments(): List<Payment> = payments.toList()
    
    fun getPaymentsBetween(user1: User, user2: User): List<Payment> =
        payments.filter { 
            (it.from == user1 && it.to == user2) || (it.from == user2 && it.to == user1) 
        }
    
    fun getTotalExpenses(): Double = expenses.sumOf { it.amount }
    
    fun getTotalExpensesByCategory(): Map<String, Double> =
        expenses.groupBy { it.category }.mapValues { (_, exps) -> exps.sumOf { it.amount } }
}
