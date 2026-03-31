package com.systemdesign.expensesharing.approach_03_event_sourcing

import com.systemdesign.expensesharing.common.*
import java.time.LocalDateTime
import java.util.UUID
import kotlin.math.abs
import kotlin.math.round

/**
 * Approach 3: Event Sourcing for Expense Sharing
 *
 * Every state change is captured as an immutable event rather than mutating state
 * directly. The current balances, debts, and group membership are all derived by
 * replaying the event log from the beginning (or from a snapshot). This gives a
 * complete audit trail and enables time-travel queries ("what did balances look
 * like last Tuesday?").
 *
 * Pattern: Event Sourcing + CQRS (Command Query Responsibility Segregation)
 *
 * Trade-offs:
 * + Complete, immutable audit trail — every change is recorded forever
 * + Time-travel: reconstruct state at any point in history
 * + Natural fit for distributed systems (events can be replicated/streamed)
 * + Easy to add new projections without changing the event store
 * + Debugging is trivial — replay events to reproduce any bug
 * - State reconstruction cost grows with event count (mitigated by snapshots)
 * - Event schema evolution requires careful versioning
 * - Eventually consistent reads if projections lag behind writes
 * - More complex than direct state mutation for simple use cases
 *
 * When to use:
 * - Audit and compliance requirements (financial systems, regulated industries)
 * - Need to answer "what happened and when" questions
 * - Multiple read models need different views of the same data
 * - System must support undo or temporal queries
 * - Events are a natural fit for the domain (expenses, payments, memberships)
 *
 * Extensibility:
 * - New event type: add a subclass of SharingEvent and handle in projections
 * - New projection: implement EventProjection and register it
 * - Snapshots: periodically persist BalanceProjection state to skip replay
 * - Event streaming: publish events to Kafka/EventBridge for downstream consumers
 */

private fun roundTwo(value: Double): Double = round(value * 100) / 100

sealed class SharingEvent {
    abstract val eventId: String
    abstract val timestamp: LocalDateTime
    abstract val version: Int

    data class GroupCreated(
        override val eventId: String = UUID.randomUUID().toString(),
        override val timestamp: LocalDateTime = LocalDateTime.now(),
        override val version: Int = 1,
        val groupId: String,
        val groupName: String,
        val initialMembers: List<User>
    ) : SharingEvent()

    data class MemberJoined(
        override val eventId: String = UUID.randomUUID().toString(),
        override val timestamp: LocalDateTime = LocalDateTime.now(),
        override val version: Int = 1,
        val groupId: String,
        val user: User
    ) : SharingEvent()

    data class MemberLeft(
        override val eventId: String = UUID.randomUUID().toString(),
        override val timestamp: LocalDateTime = LocalDateTime.now(),
        override val version: Int = 1,
        val groupId: String,
        val user: User
    ) : SharingEvent()

    data class ExpenseAdded(
        override val eventId: String = UUID.randomUUID().toString(),
        override val timestamp: LocalDateTime = LocalDateTime.now(),
        override val version: Int = 1,
        val expenseId: String = UUID.randomUUID().toString(),
        val description: String,
        val totalAmount: Double,
        val paidBy: User,
        val splitMethod: SplitMethod,
        val splitDetails: Map<User, Double>,
        val groupId: String? = null
    ) : SharingEvent()

    data class PaymentMade(
        override val eventId: String = UUID.randomUUID().toString(),
        override val timestamp: LocalDateTime = LocalDateTime.now(),
        override val version: Int = 1,
        val from: User,
        val to: User,
        val amount: Double
    ) : SharingEvent()

    data class DebtForgiven(
        override val eventId: String = UUID.randomUUID().toString(),
        override val timestamp: LocalDateTime = LocalDateTime.now(),
        override val version: Int = 1,
        val creditor: User,
        val debtor: User,
        val amount: Double
    ) : SharingEvent()
}

interface EventProjection {
    fun apply(event: SharingEvent)
    fun reset()
}

class BalanceProjection : EventProjection {
    private val debts = mutableMapOf<User, MutableMap<User, Double>>()

    override fun apply(event: SharingEvent) {
        when (event) {
            is SharingEvent.ExpenseAdded -> {
                event.splitDetails.forEach { (user, share) ->
                    if (user != event.paidBy) {
                        addDebt(user, event.paidBy, share)
                    }
                }
            }
            is SharingEvent.PaymentMade -> {
                reduceDebt(event.from, event.to, event.amount)
            }
            is SharingEvent.DebtForgiven -> {
                reduceDebt(event.debtor, event.creditor, event.amount)
            }
            else -> {}
        }
    }

    private fun addDebt(from: User, to: User, amount: Double) {
        val reverseDebt = debts[to]?.get(from) ?: 0.0
        if (reverseDebt > 0) {
            val net = amount - reverseDebt
            if (net > 0.01) {
                debts[to]?.remove(from)
                debts.getOrPut(from) { mutableMapOf() }[to] =
                    (debts[from]?.get(to) ?: 0.0) + net
            } else if (net < -0.01) {
                debts[to]!![from] = -net
            } else {
                debts[to]?.remove(from)
            }
        } else {
            debts.getOrPut(from) { mutableMapOf() }[to] =
                (debts[from]?.get(to) ?: 0.0) + amount
        }
    }

    private fun reduceDebt(from: User, to: User, amount: Double) {
        val current = debts[from]?.get(to) ?: 0.0
        val remaining = current - amount
        if (remaining <= 0.01) {
            debts[from]?.remove(to)
            if (remaining < -0.01) {
                debts.getOrPut(to) { mutableMapOf() }[from] =
                    (debts[to]?.get(from) ?: 0.0) + (-remaining)
            }
        } else {
            debts[from]!![to] = roundTwo(remaining)
        }
    }

    override fun reset() = debts.clear()

    fun getDebtFrom(from: User, to: User): Double = debts[from]?.get(to) ?: 0.0

    fun getDebtsOf(user: User): Map<User, Double> =
        debts[user]?.filter { it.value > 0.01 }?.mapValues { roundTwo(it.value) } ?: emptyMap()

    fun getCreditsOf(user: User): Map<User, Double> =
        debts.filter { it.key != user }
            .mapNotNull { (debtor, targets) ->
                val amount = targets[user] ?: 0.0
                if (amount > 0.01) debtor to roundTwo(amount) else null
            }.toMap()

    fun getNetBalance(user: User): Double {
        val owed = getCreditsOf(user).values.sum()
        val owes = getDebtsOf(user).values.sum()
        return roundTwo(owed - owes)
    }

    fun getAllNetBalances(): Map<User, Double> {
        val allUsers = debts.keys + debts.values.flatMap { it.keys }
        return allUsers.distinct()
            .map { it to getNetBalance(it) }
            .filter { abs(it.second) > 0.01 }
            .toMap()
    }

    fun generateSettlements(): List<Settlement> {
        val net = getAllNetBalances()
        if (net.isEmpty()) return emptyList()

        val debtors = net.filter { it.value < -0.01 }
            .map { it.key to -it.value }
            .sortedByDescending { it.second }
            .toMutableList()

        val creditors = net.filter { it.value > 0.01 }
            .map { it.key to it.value }
            .sortedByDescending { it.second }
            .toMutableList()

        val settlements = mutableListOf<Settlement>()
        while (debtors.isNotEmpty() && creditors.isNotEmpty()) {
            val (debtor, debtAmt) = debtors.first()
            val (creditor, creditAmt) = creditors.first()
            val transfer = minOf(debtAmt, creditAmt)

            if (transfer > 0.01) {
                settlements.add(Settlement(from = debtor, to = creditor, amount = roundTwo(transfer)))
            }

            val remainDebt = debtAmt - transfer
            val remainCredit = creditAmt - transfer
            if (remainDebt < 0.01) debtors.removeAt(0) else debtors[0] = debtor to remainDebt
            if (remainCredit < 0.01) creditors.removeAt(0) else creditors[0] = creditor to remainCredit
        }

        return settlements
    }
}

class GroupProjection : EventProjection {
    private val groups = mutableMapOf<String, Group>()

    override fun apply(event: SharingEvent) {
        when (event) {
            is SharingEvent.GroupCreated -> {
                groups[event.groupId] = Group(
                    id = event.groupId,
                    name = event.groupName,
                    members = event.initialMembers.toMutableList()
                )
            }
            is SharingEvent.MemberJoined -> {
                groups[event.groupId]?.addMember(event.user)
            }
            is SharingEvent.MemberLeft -> {
                groups[event.groupId]?.removeMember(event.user)
            }
            else -> {}
        }
    }

    override fun reset() = groups.clear()

    fun getGroup(groupId: String): Group? = groups[groupId]
    fun getAllGroups(): List<Group> = groups.values.toList()
}

class ExpenseProjection : EventProjection {
    private val expenses = mutableListOf<SharingEvent.ExpenseAdded>()
    private val payments = mutableListOf<SharingEvent.PaymentMade>()

    override fun apply(event: SharingEvent) {
        when (event) {
            is SharingEvent.ExpenseAdded -> expenses.add(event)
            is SharingEvent.PaymentMade -> payments.add(event)
            else -> {}
        }
    }

    override fun reset() {
        expenses.clear()
        payments.clear()
    }

    fun getExpenses(): List<SharingEvent.ExpenseAdded> = expenses.toList()
    fun getExpensesByGroup(groupId: String): List<SharingEvent.ExpenseAdded> =
        expenses.filter { it.groupId == groupId }
    fun getExpensesByUser(user: User): List<SharingEvent.ExpenseAdded> =
        expenses.filter { it.paidBy == user || it.splitDetails.containsKey(user) }
    fun getPayments(): List<SharingEvent.PaymentMade> = payments.toList()
    fun getTotalExpenses(): Double = expenses.sumOf { it.totalAmount }
}

class EventStore {
    private val events = mutableListOf<SharingEvent>()
    private val projections = mutableListOf<EventProjection>()

    fun registerProjection(projection: EventProjection) {
        projections.add(projection)
        events.forEach { projection.apply(it) }
    }

    fun append(event: SharingEvent) {
        events.add(event)
        projections.forEach { it.apply(event) }
    }

    fun getAllEvents(): List<SharingEvent> = events.toList()

    fun getEventsSince(timestamp: LocalDateTime): List<SharingEvent> =
        events.filter { !it.timestamp.isBefore(timestamp) }

    fun getEventsOfType(type: Class<out SharingEvent>): List<SharingEvent> =
        events.filter { type.isInstance(it) }

    fun getEventCount(): Int = events.size

    fun replay(upTo: LocalDateTime): List<EventProjection> {
        val snapshot = listOf(BalanceProjection(), GroupProjection(), ExpenseProjection())
        events.filter { !it.timestamp.isAfter(upTo) }.forEach { event ->
            snapshot.forEach { it.apply(event) }
        }
        return snapshot
    }

    fun replayAll(): List<EventProjection> {
        val snapshot = listOf(BalanceProjection(), GroupProjection(), ExpenseProjection())
        events.forEach { event ->
            snapshot.forEach { it.apply(event) }
        }
        return snapshot
    }
}

class EventSourcingSharing {
    private val eventStore = EventStore()
    val balanceProjection = BalanceProjection()
    val groupProjection = GroupProjection()
    val expenseProjection = ExpenseProjection()

    init {
        eventStore.registerProjection(balanceProjection)
        eventStore.registerProjection(groupProjection)
        eventStore.registerProjection(expenseProjection)
    }

    fun createGroup(name: String, members: List<User>): Group {
        val groupId = UUID.randomUUID().toString()
        eventStore.append(
            SharingEvent.GroupCreated(
                groupId = groupId,
                groupName = name,
                initialMembers = members
            )
        )
        return groupProjection.getGroup(groupId)!!
    }

    fun addMemberToGroup(groupId: String, user: User): Boolean {
        val group = groupProjection.getGroup(groupId) ?: return false
        if (group.members.contains(user)) return false
        eventStore.append(SharingEvent.MemberJoined(groupId = groupId, user = user))
        return true
    }

    fun removeMemberFromGroup(groupId: String, user: User): Boolean {
        val group = groupProjection.getGroup(groupId) ?: return false
        if (!group.members.contains(user)) return false
        eventStore.append(SharingEvent.MemberLeft(groupId = groupId, user = user))
        return true
    }

    fun addExpenseEqual(
        description: String,
        amount: Double,
        paidBy: User,
        participants: List<User>,
        groupId: String? = null
    ): SharingResult {
        if (amount <= 0) return SharingResult.ValidationError("Amount must be positive")
        if (participants.isEmpty()) return SharingResult.ValidationError("Must have at least one participant")

        val base = roundTwo(amount / participants.size)
        val details = mutableMapOf<User, Double>()
        var assigned = 0.0
        participants.dropLast(1).forEach { user ->
            details[user] = base
            assigned += base
        }
        details[participants.last()] = roundTwo(amount - assigned)

        return emitExpense(description, amount, paidBy, SplitMethod.EQUAL, details, groupId)
    }

    fun addExpenseExact(
        description: String,
        amount: Double,
        paidBy: User,
        exactAmounts: Map<User, Double>,
        groupId: String? = null
    ): SharingResult {
        if (amount <= 0) return SharingResult.ValidationError("Amount must be positive")
        val total = exactAmounts.values.sum()
        if (abs(total - amount) > 0.02) {
            return SharingResult.ValidationError("Exact amounts ($total) must equal total ($amount)")
        }
        return emitExpense(description, amount, paidBy, SplitMethod.EXACT, exactAmounts, groupId)
    }

    fun addExpensePercentage(
        description: String,
        amount: Double,
        paidBy: User,
        percentages: Map<User, Double>,
        groupId: String? = null
    ): SharingResult {
        if (amount <= 0) return SharingResult.ValidationError("Amount must be positive")
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

        return emitExpense(description, amount, paidBy, SplitMethod.PERCENTAGE, details, groupId)
    }

    fun addExpenseByShares(
        description: String,
        amount: Double,
        paidBy: User,
        shares: Map<User, Double>,
        groupId: String? = null
    ): SharingResult {
        if (amount <= 0) return SharingResult.ValidationError("Amount must be positive")
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

        return emitExpense(description, amount, paidBy, SplitMethod.SHARES, details, groupId)
    }

    private fun emitExpense(
        description: String,
        amount: Double,
        paidBy: User,
        method: SplitMethod,
        splitDetails: Map<User, Double>,
        groupId: String?
    ): SharingResult {
        val splitTotal = splitDetails.values.sum()
        if (abs(splitTotal - amount) > 0.02) {
            return SharingResult.ValidationError("Split amounts don't sum to total expense")
        }

        val event = SharingEvent.ExpenseAdded(
            description = description,
            totalAmount = amount,
            paidBy = paidBy,
            splitMethod = method,
            splitDetails = splitDetails,
            groupId = groupId
        )

        eventStore.append(event)

        val expense = SharedExpense(
            id = event.expenseId,
            description = description,
            totalAmount = amount,
            paidBy = paidBy,
            splitMethod = method,
            participants = splitDetails.keys.toList(),
            splitDetails = splitDetails,
            groupId = groupId,
            timestamp = event.timestamp
        )

        return SharingResult.Success(expense)
    }

    fun recordPayment(from: User, to: User, amount: Double): Boolean {
        if (amount <= 0 || from == to) return false
        val currentDebt = balanceProjection.getDebtFrom(from, to)
        if (currentDebt <= 0.01) return false

        eventStore.append(SharingEvent.PaymentMade(from = from, to = to, amount = amount))
        return true
    }

    fun forgiveDebt(creditor: User, debtor: User, amount: Double): Boolean {
        if (amount <= 0 || creditor == debtor) return false
        val currentDebt = balanceProjection.getDebtFrom(debtor, creditor)
        if (currentDebt <= 0.01) return false

        val forgivenAmount = minOf(amount, currentDebt)
        eventStore.append(
            SharingEvent.DebtForgiven(creditor = creditor, debtor = debtor, amount = forgivenAmount)
        )
        return true
    }

    fun simplifyDebts(): SettlementResult {
        val settlements = balanceProjection.generateSettlements()
        return if (settlements.isEmpty()) {
            SettlementResult.NoSettlementsNeeded()
        } else {
            SettlementResult.Success(settlements)
        }
    }

    fun getNetBalance(user: User): Double = balanceProjection.getNetBalance(user)
    fun getDebtsOf(user: User): Map<User, Double> = balanceProjection.getDebtsOf(user)
    fun getCreditsOf(user: User): Map<User, Double> = balanceProjection.getCreditsOf(user)

    fun getBalancesAtTime(timestamp: LocalDateTime): Map<User, Double> {
        val projections = eventStore.replay(timestamp)
        val balanceProj = projections.filterIsInstance<BalanceProjection>().first()
        return balanceProj.getAllNetBalances()
    }

    fun getEventLog(): List<SharingEvent> = eventStore.getAllEvents()
    fun getEventCount(): Int = eventStore.getEventCount()

    fun getExpenses(): List<SharingEvent.ExpenseAdded> = expenseProjection.getExpenses()
    fun getExpensesByGroup(groupId: String): List<SharingEvent.ExpenseAdded> =
        expenseProjection.getExpensesByGroup(groupId)
    fun getTotalExpenses(): Double = expenseProjection.getTotalExpenses()
}
