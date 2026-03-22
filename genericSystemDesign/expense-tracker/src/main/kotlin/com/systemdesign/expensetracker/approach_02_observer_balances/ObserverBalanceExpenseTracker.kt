package com.systemdesign.expensetracker.approach_02_observer_balances

import com.systemdesign.expensetracker.common.*
import java.time.LocalDateTime
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * Approach 2: Observer Pattern for Balance Updates
 * 
 * Balance observers are notified in real-time when expenses or payments occur.
 * This enables reactive features like notifications, automatic settlement suggestions,
 * and debt tracking without polling.
 * 
 * Pattern: Observer
 * 
 * Trade-offs:
 * + Real-time notifications to multiple listeners
 * + Loose coupling between expense tracking and notification logic
 * + Easy to add new observers without modifying core logic
 * - Potential for observer notification ordering issues
 * - Must manage observer lifecycle to prevent memory leaks
 * 
 * When to use:
 * - When multiple components need to react to balance changes
 * - When real-time updates are required (notifications, UI updates)
 * - When you want to decouple event generation from event handling
 * 
 * Extensibility:
 * - New notification type: Implement BalanceObserver interface
 * - New event type: Add method to observer interface
 */

interface BalanceObserver {
    fun onExpenseAdded(expense: Expense)
    fun onPaymentMade(payment: Payment)
    fun onBalanceChanged(user: User, oldBalance: Balance, newBalance: Balance)
    fun onSettlementSuggested(settlements: List<Settlement>)
}

abstract class BaseBalanceObserver : BalanceObserver {
    override fun onExpenseAdded(expense: Expense) {}
    override fun onPaymentMade(payment: Payment) {}
    override fun onBalanceChanged(user: User, oldBalance: Balance, newBalance: Balance) {}
    override fun onSettlementSuggested(settlements: List<Settlement>) {}
}

class BalanceNotifier : BalanceObserver {
    private val notifications = mutableListOf<BalanceNotification>()
    
    override fun onExpenseAdded(expense: Expense) {
        expense.splits.forEach { split ->
            if (split.user != expense.paidBy) {
                notifications.add(
                    BalanceNotification(
                        type = NotificationType.EXPENSE_ADDED,
                        user = split.user,
                        message = "You owe ${expense.paidBy.name} $${String.format("%.2f", split.amount)} for '${expense.description}'",
                        timestamp = LocalDateTime.now()
                    )
                )
            }
        }
        
        val totalOwed = expense.splits.filter { it.user != expense.paidBy }.sumOf { it.amount }
        if (totalOwed > 0) {
            notifications.add(
                BalanceNotification(
                    type = NotificationType.EXPENSE_ADDED,
                    user = expense.paidBy,
                    message = "You paid $${String.format("%.2f", expense.amount)} for '${expense.description}'. Others owe you $${String.format("%.2f", totalOwed)}",
                    timestamp = LocalDateTime.now()
                )
            )
        }
    }
    
    override fun onPaymentMade(payment: Payment) {
        notifications.add(
            BalanceNotification(
                type = NotificationType.PAYMENT_RECEIVED,
                user = payment.to,
                message = "${payment.from.name} paid you $${String.format("%.2f", payment.amount)}",
                timestamp = LocalDateTime.now()
            )
        )
        
        notifications.add(
            BalanceNotification(
                type = NotificationType.PAYMENT_SENT,
                user = payment.from,
                message = "You paid ${payment.to.name} $${String.format("%.2f", payment.amount)}",
                timestamp = LocalDateTime.now()
            )
        )
    }
    
    override fun onBalanceChanged(user: User, oldBalance: Balance, newBalance: Balance) {
        val oldNet = oldBalance.netBalance
        val newNet = newBalance.netBalance
        
        if (abs(newNet) < 0.01 && abs(oldNet) >= 0.01) {
            notifications.add(
                BalanceNotification(
                    type = NotificationType.BALANCE_SETTLED,
                    user = user,
                    message = "All your balances are now settled!",
                    timestamp = LocalDateTime.now()
                )
            )
        }
    }
    
    override fun onSettlementSuggested(settlements: List<Settlement>) {}
    
    fun getNotifications(): List<BalanceNotification> = notifications.toList()
    
    fun getNotificationsForUser(user: User): List<BalanceNotification> = 
        notifications.filter { it.user == user }
    
    fun clearNotifications() = notifications.clear()
}

class SettlementSuggester : BalanceObserver {
    private var currentSuggestions = listOf<Settlement>()
    private var suggestionThreshold = 10.0
    
    fun setThreshold(threshold: Double) {
        suggestionThreshold = threshold
    }
    
    override fun onExpenseAdded(expense: Expense) {}
    
    override fun onPaymentMade(payment: Payment) {}
    
    override fun onBalanceChanged(user: User, oldBalance: Balance, newBalance: Balance) {
        if (newBalance.totalOwes > suggestionThreshold) {
            val suggestions = newBalance.owes
                .filter { it.value > suggestionThreshold }
                .map { (creditor, amount) -> Settlement(from = user, to = creditor, amount = amount) }
            
            if (suggestions.isNotEmpty()) {
                currentSuggestions = suggestions
            }
        }
    }
    
    override fun onSettlementSuggested(settlements: List<Settlement>) {
        currentSuggestions = settlements
    }
    
    fun getSuggestions(): List<Settlement> = currentSuggestions
}

class DebtTracker : BalanceObserver {
    private val debtHistory = mutableListOf<DebtEvent>()
    private val currentDebts = mutableMapOf<Pair<User, User>, Double>()
    
    override fun onExpenseAdded(expense: Expense) {
        expense.splits.forEach { split ->
            if (split.user != expense.paidBy) {
                val key = split.user to expense.paidBy
                val currentDebt = currentDebts[key] ?: 0.0
                val newDebt = currentDebt + split.amount
                currentDebts[key] = newDebt
                
                debtHistory.add(
                    DebtEvent(
                        type = DebtEventType.DEBT_INCREASED,
                        debtor = split.user,
                        creditor = expense.paidBy,
                        amount = split.amount,
                        newTotal = newDebt,
                        reason = "Expense: ${expense.description}",
                        timestamp = LocalDateTime.now()
                    )
                )
            }
        }
    }
    
    override fun onPaymentMade(payment: Payment) {
        val key = payment.from to payment.to
        val currentDebt = currentDebts[key] ?: 0.0
        val newDebt = maxOf(0.0, currentDebt - payment.amount)
        
        if (newDebt < 0.01) {
            currentDebts.remove(key)
        } else {
            currentDebts[key] = newDebt
        }
        
        debtHistory.add(
            DebtEvent(
                type = DebtEventType.DEBT_DECREASED,
                debtor = payment.from,
                creditor = payment.to,
                amount = payment.amount,
                newTotal = newDebt,
                reason = "Payment",
                timestamp = LocalDateTime.now()
            )
        )
    }
    
    override fun onBalanceChanged(user: User, oldBalance: Balance, newBalance: Balance) {}
    
    override fun onSettlementSuggested(settlements: List<Settlement>) {}
    
    fun getDebtHistory(): List<DebtEvent> = debtHistory.toList()
    
    fun getDebtHistoryBetween(user1: User, user2: User): List<DebtEvent> =
        debtHistory.filter { 
            (it.debtor == user1 && it.creditor == user2) || 
            (it.debtor == user2 && it.creditor == user1) 
        }
    
    fun getCurrentDebts(): Map<Pair<User, User>, Double> = currentDebts.toMap()
    
    fun getDebtsBetween(user1: User, user2: User): Double {
        val debt1to2 = currentDebts[user1 to user2] ?: 0.0
        val debt2to1 = currentDebts[user2 to user1] ?: 0.0
        return debt1to2 - debt2to1
    }
}

data class BalanceNotification(
    val type: NotificationType,
    val user: User,
    val message: String,
    val timestamp: LocalDateTime
)

enum class NotificationType {
    EXPENSE_ADDED,
    PAYMENT_RECEIVED,
    PAYMENT_SENT,
    BALANCE_SETTLED,
    SETTLEMENT_SUGGESTED
}

data class DebtEvent(
    val type: DebtEventType,
    val debtor: User,
    val creditor: User,
    val amount: Double,
    val newTotal: Double,
    val reason: String,
    val timestamp: LocalDateTime
)

enum class DebtEventType {
    DEBT_INCREASED,
    DEBT_DECREASED,
    DEBT_SETTLED
}

private fun roundToTwoDecimals(value: Double): Double {
    return kotlin.math.round(value * 100) / 100
}

class ObserverBalanceExpenseTracker {
    private val expenses = mutableListOf<Expense>()
    private val payments = mutableListOf<Payment>()
    private val groups = mutableMapOf<String, Group>()
    private val userBalances = mutableMapOf<User, MutableMap<User, Double>>()
    
    private val observers = CopyOnWriteArrayList<BalanceObserver>()
    
    fun addObserver(observer: BalanceObserver) {
        observers.add(observer)
    }
    
    fun removeObserver(observer: BalanceObserver) {
        observers.remove(observer)
    }
    
    fun createGroup(name: String, members: List<User>): Group {
        val group = Group(name = name, members = members.toMutableList())
        groups[group.id] = group
        return group
    }
    
    fun addExpense(
        description: String,
        amount: Double,
        paidBy: User,
        splits: List<Split>,
        category: String = "General",
        groupId: String? = null
    ): ExpenseResult {
        if (amount <= 0) {
            return ExpenseResult.ValidationError("Amount must be positive")
        }
        
        if (splits.isEmpty()) {
            return ExpenseResult.ValidationError("Must have at least one split")
        }
        
        val totalSplit = splits.sumOf { it.amount }
        if (abs(totalSplit - amount) > 0.01) {
            return ExpenseResult.ValidationError("Split amounts ($totalSplit) don't match expense amount ($amount)")
        }
        
        val expense = Expense(
            description = description,
            amount = amount,
            paidBy = paidBy,
            splits = splits,
            category = category,
            groupId = groupId
        )
        
        expenses.add(expense)
        
        val affectedUsers = mutableSetOf<User>()
        affectedUsers.add(paidBy)
        
        splits.forEach { split ->
            affectedUsers.add(split.user)
            if (split.user != paidBy) {
                val oldBalance = getBalance(split.user)
                addDebt(split.user, paidBy, split.amount)
                val newBalance = getBalance(split.user)
                
                observers.forEach { it.onBalanceChanged(split.user, oldBalance, newBalance) }
            }
        }
        
        val payerOldBalance = getBalance(paidBy)
        val payerNewBalance = getBalance(paidBy)
        observers.forEach { it.onBalanceChanged(paidBy, payerOldBalance, payerNewBalance) }
        
        observers.forEach { it.onExpenseAdded(expense) }
        
        checkAndNotifySettlements()
        
        return ExpenseResult.Success(expense)
    }
    
    fun addExpenseEqual(
        description: String,
        amount: Double,
        paidBy: User,
        participants: List<User>,
        category: String = "General",
        groupId: String? = null
    ): ExpenseResult {
        if (participants.isEmpty()) {
            return ExpenseResult.ValidationError("Must have at least one participant")
        }
        
        val splitAmount = amount / participants.size
        val splits = participants.map { Split(it, roundToTwoDecimals(splitAmount)) }
        
        val adjustedSplits = adjustForRemainder(splits, amount)
        
        return addExpense(description, amount, paidBy, adjustedSplits, category, groupId)
    }
    
    private fun adjustForRemainder(splits: List<Split>, totalAmount: Double): List<Split> {
        val currentTotal = splits.sumOf { it.amount }
        val remainder = totalAmount - currentTotal
        
        if (abs(remainder) < 0.01) return splits
        
        val adjustedSplits = splits.toMutableList()
        val lastSplit = adjustedSplits.last()
        adjustedSplits[adjustedSplits.lastIndex] = lastSplit.copy(
            amount = roundToTwoDecimals(lastSplit.amount + remainder)
        )
        
        return adjustedSplits
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
        
        val oldFromBalance = getBalance(from)
        val oldToBalance = getBalance(to)
        
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
        
        val newFromBalance = getBalance(from)
        val newToBalance = getBalance(to)
        
        observers.forEach { 
            it.onPaymentMade(payment)
            it.onBalanceChanged(from, oldFromBalance, newFromBalance)
            it.onBalanceChanged(to, oldToBalance, newToBalance)
        }
        
        checkAndNotifySettlements()
        
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
    
    private fun checkAndNotifySettlements() {
        val result = suggestSettlements()
        if (result is SettlementResult.Success) {
            observers.forEach { it.onSettlementSuggested(result.settlements) }
        }
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
    fun getPayments(): List<Payment> = payments.toList()
    fun getTotalExpenses(): Double = expenses.sumOf { it.amount }
}
