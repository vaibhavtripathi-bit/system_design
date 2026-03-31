package com.systemdesign.expensesharing.approach_02_mediator_settlement

import com.systemdesign.expensesharing.common.*
import java.time.LocalDateTime
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.round

/**
 * Approach 2: Mediator Pattern for Settlement Coordination
 *
 * A central SettlementMediator coordinates all interactions between group members.
 * Members never communicate debts directly — instead, the mediator tracks every
 * balance, generates optimal settlements, and pushes notifications through the
 * observer interface. This decouples expense recording from settlement logic and
 * notification delivery.
 *
 * Pattern: Mediator + Observer
 *
 * Trade-offs:
 * + Single source of truth for all balances — no inconsistency between members
 * + Members are fully decoupled; adding/removing members doesn't affect others
 * + Observer notifications enable reactive UIs, push alerts, and audit logging
 * + Easy to add cross-cutting concerns (rate limiting, fraud detection) in the mediator
 * - Mediator can become a god object if not carefully scoped
 * - Single point of failure — mediator must be highly available
 * - Observer ordering is non-deterministic; side-effect-heavy observers can cause issues
 *
 * When to use:
 * - Many-to-many interactions between members that would create a tangled web
 * - You need centralized validation and business rules for settlements
 * - Real-time notifications to multiple consumers (UI, email, analytics)
 * - The system needs to enforce settlement policies (minimum amounts, deadlines)
 *
 * Extensibility:
 * - New notification channel: implement ExpenseSharingObserver
 * - Settlement policy: add validation rules in SettlementMediator.generateSettlements
 * - Multi-currency: partition balances by currency in MemberAccount
 */

private fun roundTwo(value: Double): Double = round(value * 100) / 100

data class MemberAccount(
    val user: User,
    val debts: MutableMap<User, Double> = mutableMapOf(),
    val credits: MutableMap<User, Double> = mutableMapOf()
) {
    val netBalance: Double
        get() = credits.values.sum() - debts.values.sum()

    val totalOwes: Double
        get() = debts.values.sum()

    val totalOwed: Double
        get() = credits.values.sum()
}

data class ExpenseNotification(
    val type: NotificationType,
    val targetUser: User,
    val message: String,
    val relatedExpense: SharedExpense? = null,
    val timestamp: LocalDateTime = LocalDateTime.now()
)

enum class NotificationType {
    EXPENSE_ADDED,
    PAYMENT_RECEIVED,
    PAYMENT_SENT,
    SETTLEMENT_SUGGESTED,
    MEMBER_JOINED,
    BALANCE_CHANGED
}

class NotificationObserver : ExpenseSharingObserver {
    private val notifications = mutableListOf<ExpenseNotification>()

    override fun onExpenseAdded(expense: SharedExpense) {
        expense.splitDetails.forEach { (user, amount) ->
            if (user != expense.paidBy) {
                notifications.add(
                    ExpenseNotification(
                        type = NotificationType.EXPENSE_ADDED,
                        targetUser = user,
                        message = "You owe ${expense.paidBy.name} $${String.format("%.2f", amount)} for '${expense.description}'",
                        relatedExpense = expense
                    )
                )
            }
        }

        val totalOwed = expense.splitDetails.filter { it.key != expense.paidBy }.values.sum()
        if (totalOwed > 0) {
            notifications.add(
                ExpenseNotification(
                    type = NotificationType.EXPENSE_ADDED,
                    targetUser = expense.paidBy,
                    message = "You paid $${String.format("%.2f", expense.totalAmount)} for '${expense.description}'. Others owe you $${String.format("%.2f", totalOwed)}",
                    relatedExpense = expense
                )
            )
        }
    }

    override fun onSettlementGenerated(settlements: List<Settlement>) {
        settlements.forEach { s ->
            notifications.add(
                ExpenseNotification(
                    type = NotificationType.SETTLEMENT_SUGGESTED,
                    targetUser = s.from,
                    message = "Suggested: pay ${s.to.name} $${String.format("%.2f", s.amount)}"
                )
            )
        }
    }

    override fun onBalanceChanged(user: User, netBalance: Double) {
        notifications.add(
            ExpenseNotification(
                type = NotificationType.BALANCE_CHANGED,
                targetUser = user,
                message = if (netBalance >= 0) "You are owed $${String.format("%.2f", netBalance)}"
                else "You owe $${String.format("%.2f", -netBalance)} overall"
            )
        )
    }

    override fun onMemberJoined(group: Group, user: User) {
        notifications.add(
            ExpenseNotification(
                type = NotificationType.MEMBER_JOINED,
                targetUser = user,
                message = "You joined group '${group.name}'"
            )
        )
    }

    fun getNotifications(): List<ExpenseNotification> = notifications.toList()
    fun getNotificationsForUser(user: User): List<ExpenseNotification> = notifications.filter { it.targetUser == user }
    fun clearNotifications() = notifications.clear()
}

class BalanceAuditor : ExpenseSharingObserver {
    private val balanceSnapshots = mutableListOf<BalanceSnapshot>()

    override fun onExpenseAdded(expense: SharedExpense) {
        balanceSnapshots.add(
            BalanceSnapshot(
                trigger = "Expense: ${expense.description}",
                affectedUsers = expense.participants,
                timestamp = LocalDateTime.now()
            )
        )
    }

    override fun onSettlementGenerated(settlements: List<Settlement>) {
        val users = settlements.flatMap { listOf(it.from, it.to) }.distinct()
        balanceSnapshots.add(
            BalanceSnapshot(
                trigger = "Settlement generated (${settlements.size} transactions)",
                affectedUsers = users,
                timestamp = LocalDateTime.now()
            )
        )
    }

    override fun onBalanceChanged(user: User, netBalance: Double) {}
    override fun onMemberJoined(group: Group, user: User) {}

    fun getSnapshots(): List<BalanceSnapshot> = balanceSnapshots.toList()
}

data class BalanceSnapshot(
    val trigger: String,
    val affectedUsers: List<User>,
    val timestamp: LocalDateTime
)

class SettlementMediator {
    private val accounts = mutableMapOf<User, MemberAccount>()
    private val groups = mutableMapOf<String, Group>()
    private val expenses = mutableListOf<SharedExpense>()
    private val observers = CopyOnWriteArrayList<ExpenseSharingObserver>()

    private var minimumSettlementAmount = 0.01

    fun setMinimumSettlementAmount(amount: Double) {
        require(amount >= 0) { "Minimum settlement amount cannot be negative" }
        minimumSettlementAmount = amount
    }

    fun addObserver(observer: ExpenseSharingObserver) {
        observers.add(observer)
    }

    fun removeObserver(observer: ExpenseSharingObserver) {
        observers.remove(observer)
    }

    fun createGroup(name: String, members: List<User>): Group {
        val group = Group(name = name, members = members.toMutableList())
        groups[group.id] = group
        members.forEach { user ->
            accounts.getOrPut(user) { MemberAccount(user) }
            observers.forEach { it.onMemberJoined(group, user) }
        }
        return group
    }

    fun addMemberToGroup(groupId: String, user: User): Boolean {
        val group = groups[groupId] ?: return false
        if (group.members.contains(user)) return false
        group.addMember(user)
        accounts.getOrPut(user) { MemberAccount(user) }
        observers.forEach { it.onMemberJoined(group, user) }
        return true
    }

    fun addExpenseEqual(
        description: String,
        amount: Double,
        paidBy: User,
        participants: List<User>,
        groupId: String? = null
    ): SharingResult {
        if (participants.isEmpty()) return SharingResult.ValidationError("Must have at least one participant")
        val base = roundTwo(amount / participants.size)
        val details = mutableMapOf<User, Double>()
        var assigned = 0.0
        participants.dropLast(1).forEach { user ->
            details[user] = base
            assigned += base
        }
        details[participants.last()] = roundTwo(amount - assigned)
        return recordExpense(description, amount, paidBy, SplitMethod.EQUAL, participants, details, groupId)
    }

    fun addExpenseExact(
        description: String,
        amount: Double,
        paidBy: User,
        exactAmounts: Map<User, Double>,
        groupId: String? = null
    ): SharingResult {
        val total = exactAmounts.values.sum()
        if (abs(total - amount) > 0.02) {
            return SharingResult.ValidationError("Exact amounts ($total) must equal total ($amount)")
        }
        return recordExpense(description, amount, paidBy, SplitMethod.EXACT, exactAmounts.keys.toList(), exactAmounts, groupId)
    }

    fun addExpensePercentage(
        description: String,
        amount: Double,
        paidBy: User,
        percentages: Map<User, Double>,
        groupId: String? = null
    ): SharingResult {
        val totalPct = percentages.values.sum()
        if (abs(totalPct - 100.0) > 0.02) {
            return SharingResult.ValidationError("Percentages must sum to 100, got $totalPct")
        }
        val details = mutableMapOf<User, Double>()
        var assigned = 0.0
        val ordered = percentages.keys.toList()
        ordered.dropLast(1).forEach { user ->
            val share = roundTwo(amount * (percentages[user]!! / 100.0))
            details[user] = share
            assigned += share
        }
        ordered.lastOrNull()?.let { details[it] = roundTwo(amount - assigned) }
        return recordExpense(description, amount, paidBy, SplitMethod.PERCENTAGE, ordered, details, groupId)
    }

    fun addExpenseByShares(
        description: String,
        amount: Double,
        paidBy: User,
        shares: Map<User, Double>,
        groupId: String? = null
    ): SharingResult {
        if (shares.values.any { it <= 0 }) {
            return SharingResult.ValidationError("All shares must be positive")
        }
        val totalShares = shares.values.sum()
        val details = mutableMapOf<User, Double>()
        var assigned = 0.0
        val ordered = shares.keys.toList()
        ordered.dropLast(1).forEach { user ->
            val share = roundTwo(amount * (shares[user]!! / totalShares))
            details[user] = share
            assigned += share
        }
        ordered.lastOrNull()?.let { details[it] = roundTwo(amount - assigned) }
        return recordExpense(description, amount, paidBy, SplitMethod.SHARES, ordered, details, groupId)
    }

    private fun recordExpense(
        description: String,
        amount: Double,
        paidBy: User,
        method: SplitMethod,
        participants: List<User>,
        splitDetails: Map<User, Double>,
        groupId: String?
    ): SharingResult {
        if (amount <= 0) return SharingResult.ValidationError("Amount must be positive")
        if (participants.isEmpty()) return SharingResult.ValidationError("Must have at least one participant")

        val expense = SharedExpense(
            description = description,
            totalAmount = amount,
            paidBy = paidBy,
            splitMethod = method,
            participants = participants,
            splitDetails = splitDetails,
            groupId = groupId
        )

        if (!expense.validateSplits()) {
            return SharingResult.ValidationError("Split amounts don't sum to total expense")
        }

        expenses.add(expense)
        val payerAccount = accounts.getOrPut(paidBy) { MemberAccount(paidBy) }

        splitDetails.forEach { (user, share) ->
            if (user != paidBy) {
                val debtorAccount = accounts.getOrPut(user) { MemberAccount(user) }
                applyDebt(debtorAccount, payerAccount, share)
            }
        }

        observers.forEach { it.onExpenseAdded(expense) }
        notifyBalanceChanges(participants)

        return SharingResult.Success(expense)
    }

    private fun applyDebt(debtor: MemberAccount, creditor: MemberAccount, amount: Double) {
        val existingReverse = creditor.debts[debtor.user] ?: 0.0
        if (existingReverse > 0) {
            val net = amount - existingReverse
            if (net > 0.01) {
                creditor.debts.remove(debtor.user)
                debtor.credits.remove(creditor.user)
                debtor.debts[creditor.user] = (debtor.debts[creditor.user] ?: 0.0) + net
                creditor.credits[debtor.user] = (creditor.credits[debtor.user] ?: 0.0) + net
            } else if (net < -0.01) {
                creditor.debts[debtor.user] = -net
                debtor.credits[creditor.user] = -net
                debtor.debts.remove(creditor.user)
                creditor.credits.remove(debtor.user)
            } else {
                creditor.debts.remove(debtor.user)
                debtor.credits.remove(creditor.user)
            }
        } else {
            debtor.debts[creditor.user] = (debtor.debts[creditor.user] ?: 0.0) + amount
            creditor.credits[debtor.user] = (creditor.credits[debtor.user] ?: 0.0) + amount
        }
    }

    fun recordPayment(from: User, to: User, amount: Double): Boolean {
        if (amount <= 0 || from == to) return false
        val fromAccount = accounts[from] ?: return false
        val toAccount = accounts[to] ?: return false
        val currentDebt = fromAccount.debts[to] ?: return false
        if (currentDebt <= 0.01) return false

        val newDebt = currentDebt - amount
        if (newDebt <= 0.01) {
            fromAccount.debts.remove(to)
            toAccount.credits.remove(from)
            if (newDebt < -0.01) {
                toAccount.debts[from] = -newDebt
                fromAccount.credits[to] = -newDebt
            }
        } else {
            fromAccount.debts[to] = roundTwo(newDebt)
            toAccount.credits[from] = roundTwo(newDebt)
        }

        notifyBalanceChanges(listOf(from, to))
        return true
    }

    fun generateSettlements(): SettlementResult {
        val netBalances = mutableMapOf<User, Double>()
        accounts.forEach { (user, account) ->
            val net = account.netBalance
            if (abs(net) > 0.01) netBalances[user] = roundTwo(net)
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
            val (debtor, debtAmt) = debtors.first()
            val (creditor, creditAmt) = creditors.first()
            val transfer = minOf(debtAmt, creditAmt)

            if (transfer >= minimumSettlementAmount) {
                settlements.add(Settlement(from = debtor, to = creditor, amount = roundTwo(transfer)))
            }

            val remainDebt = debtAmt - transfer
            val remainCredit = creditAmt - transfer
            if (remainDebt < 0.01) debtors.removeAt(0) else debtors[0] = debtor to remainDebt
            if (remainCredit < 0.01) creditors.removeAt(0) else creditors[0] = creditor to remainCredit
        }

        if (settlements.isEmpty()) return SettlementResult.NoSettlementsNeeded()

        observers.forEach { it.onSettlementGenerated(settlements) }
        return SettlementResult.Success(settlements)
    }

    private fun notifyBalanceChanges(users: List<User>) {
        users.forEach { user ->
            val account = accounts[user] ?: return@forEach
            observers.forEach { it.onBalanceChanged(user, roundTwo(account.netBalance)) }
        }
    }

    fun getAccount(user: User): MemberAccount? = accounts[user]

    fun getNetBalance(user: User): Double = roundTwo(accounts[user]?.netBalance ?: 0.0)

    fun getDebtsOf(user: User): Map<User, Double> =
        accounts[user]?.debts?.filter { it.value > 0.01 }?.mapValues { roundTwo(it.value) } ?: emptyMap()

    fun getCreditsOf(user: User): Map<User, Double> =
        accounts[user]?.credits?.filter { it.value > 0.01 }?.mapValues { roundTwo(it.value) } ?: emptyMap()

    fun getExpenses(): List<SharedExpense> = expenses.toList()

    fun getExpensesByGroup(groupId: String): List<SharedExpense> =
        expenses.filter { it.groupId == groupId }

    fun getTotalExpenses(): Double = expenses.sumOf { it.totalAmount }
}
