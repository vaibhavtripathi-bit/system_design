package com.systemdesign.expensesharing.approach_01_graph_simplification

import com.systemdesign.expensesharing.common.*
import kotlin.math.abs
import kotlin.math.round

/**
 * Approach 1: Graph-Based Debt Simplification
 *
 * Debts between group members are modeled as a directed weighted graph where each
 * edge represents money owed. The key insight is that for settlement purposes, only
 * net balances matter — intermediate debts can be collapsed. A greedy algorithm
 * repeatedly matches the largest creditor with the largest debtor to produce a
 * minimal set of settlement transactions.
 *
 * Pattern: Graph + Greedy Optimization
 *
 * Trade-offs:
 * + Produces near-optimal number of transactions (at most N-1 for N people)
 * + Simple mental model — debts are just edges in a graph
 * + Efficient O(E + N log N) where E = edges, N = people
 * + Naturally handles cycles (A→B→C→A collapses to net balances)
 * - Loses individual debt provenance after simplification
 * - Greedy result is not always globally optimal (NP-hard in general)
 * - No built-in audit trail of how simplified debts were derived
 *
 * When to use:
 * - Primary goal is minimizing the number of money transfers
 * - Group size is moderate (works well up to hundreds of members)
 * - You don't need to preserve which specific expense caused which debt
 * - Real-time settlement suggestions after each expense
 *
 * Extensibility:
 * - New split method: implement SplitCalculator and register it
 * - Currency support: add currency field to DebtEdge and partition graph per currency
 * - Weighted simplification: prioritize settling certain pairs first
 */

private fun roundTwo(value: Double): Double = round(value * 100) / 100

interface SplitCalculator {
    fun calculate(amount: Double, participants: List<User>, metadata: Map<User, Double> = emptyMap()): Map<User, Double>
}

class EqualSplitCalculator : SplitCalculator {
    override fun calculate(amount: Double, participants: List<User>, metadata: Map<User, Double>): Map<User, Double> {
        require(participants.isNotEmpty()) { "Must have at least one participant" }
        val base = roundTwo(amount / participants.size)
        val result = mutableMapOf<User, Double>()
        var assigned = 0.0

        participants.dropLast(1).forEach { user ->
            result[user] = base
            assigned += base
        }
        result[participants.last()] = roundTwo(amount - assigned)
        return result
    }
}

class ExactSplitCalculator : SplitCalculator {
    override fun calculate(amount: Double, participants: List<User>, metadata: Map<User, Double>): Map<User, Double> {
        require(metadata.isNotEmpty()) { "Exact split requires amounts for each participant" }
        val total = metadata.values.sum()
        require(abs(total - amount) < 0.02) { "Exact amounts ($total) must equal total ($amount)" }
        return metadata.mapValues { roundTwo(it.value) }
    }
}

class PercentageSplitCalculator : SplitCalculator {
    override fun calculate(amount: Double, participants: List<User>, metadata: Map<User, Double>): Map<User, Double> {
        require(metadata.isNotEmpty()) { "Percentage split requires percentages for each participant" }
        val totalPct = metadata.values.sum()
        require(abs(totalPct - 100.0) < 0.02) { "Percentages must sum to 100, got $totalPct" }

        val result = mutableMapOf<User, Double>()
        var assigned = 0.0
        val ordered = participants.filter { metadata.containsKey(it) }

        ordered.dropLast(1).forEach { user ->
            val share = roundTwo(amount * (metadata[user]!! / 100.0))
            result[user] = share
            assigned += share
        }
        ordered.lastOrNull()?.let { result[it] = roundTwo(amount - assigned) }
        return result
    }
}

class SharesSplitCalculator : SplitCalculator {
    override fun calculate(amount: Double, participants: List<User>, metadata: Map<User, Double>): Map<User, Double> {
        require(metadata.isNotEmpty()) { "Shares split requires share counts for each participant" }
        require(metadata.values.all { it > 0 }) { "All shares must be positive" }

        val totalShares = metadata.values.sum()
        val result = mutableMapOf<User, Double>()
        var assigned = 0.0
        val ordered = participants.filter { metadata.containsKey(it) }

        ordered.dropLast(1).forEach { user ->
            val share = roundTwo(amount * (metadata[user]!! / totalShares))
            result[user] = share
            assigned += share
        }
        ordered.lastOrNull()?.let { result[it] = roundTwo(amount - assigned) }
        return result
    }
}

class DebtGraph {
    private val adjacency = mutableMapOf<User, MutableMap<User, Double>>()

    fun addEdge(from: User, to: User, amount: Double) {
        if (from == to || amount <= 0) return

        val reverse = adjacency.getOrPut(to) { mutableMapOf() }[from] ?: 0.0
        if (reverse > 0) {
            val net = amount - reverse
            if (net > 0.01) {
                adjacency[to]!!.remove(from)
                if (adjacency[to]!!.isEmpty()) adjacency.remove(to)
                adjacency.getOrPut(from) { mutableMapOf() }[to] =
                    (adjacency[from]?.get(to) ?: 0.0) + net
            } else if (net < -0.01) {
                adjacency[to]!![from] = -net
            } else {
                adjacency[to]!!.remove(from)
                if (adjacency[to]!!.isEmpty()) adjacency.remove(to)
            }
        } else {
            adjacency.getOrPut(from) { mutableMapOf() }[to] =
                (adjacency[from]?.get(to) ?: 0.0) + amount
        }
    }

    fun removeEdge(from: User, to: User, amount: Double) {
        val current = adjacency[from]?.get(to) ?: return
        val remaining = current - amount
        if (remaining <= 0.01) {
            adjacency[from]?.remove(to)
            if (adjacency[from]?.isEmpty() == true) adjacency.remove(from)
        } else {
            adjacency[from]!![to] = roundTwo(remaining)
        }
    }

    fun getEdges(): List<DebtEdge> =
        adjacency.flatMap { (from, targets) ->
            targets.filter { it.value > 0.01 }.map { (to, amount) -> DebtEdge(from, to, roundTwo(amount)) }
        }

    fun getNetBalances(): Map<User, Double> {
        val balances = mutableMapOf<User, Double>()
        adjacency.forEach { (from, targets) ->
            targets.forEach { (to, amount) ->
                balances[from] = (balances[from] ?: 0.0) - amount
                balances[to] = (balances[to] ?: 0.0) + amount
            }
        }
        return balances.mapValues { roundTwo(it.value) }.filter { abs(it.value) > 0.01 }
    }

    fun getDebtFrom(from: User, to: User): Double =
        adjacency[from]?.get(to) ?: 0.0

    fun getDebtsOf(user: User): Map<User, Double> =
        adjacency[user]?.filter { it.value > 0.01 }?.mapValues { roundTwo(it.value) } ?: emptyMap()

    fun getCreditsOf(user: User): Map<User, Double> =
        adjacency.filter { it.key != user }
            .mapNotNull { (debtor, targets) ->
                val amount = targets[user] ?: 0.0
                if (amount > 0.01) debtor to roundTwo(amount) else null
            }.toMap()

    fun simplify(): List<Settlement> {
        val net = getNetBalances()
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

    fun clear() = adjacency.clear()
}

class GraphSimplificationSharing {
    private val groups = mutableMapOf<String, Group>()
    private val expenses = mutableListOf<SharedExpense>()
    private val graph = DebtGraph()

    private val calculators = mapOf<SplitMethod, SplitCalculator>(
        SplitMethod.EQUAL to EqualSplitCalculator(),
        SplitMethod.EXACT to ExactSplitCalculator(),
        SplitMethod.PERCENTAGE to PercentageSplitCalculator(),
        SplitMethod.SHARES to SharesSplitCalculator()
    )

    fun createGroup(name: String, members: List<User>): Group {
        val group = Group(name = name, members = members.toMutableList())
        groups[group.id] = group
        return group
    }

    fun getGroup(groupId: String): Group? = groups[groupId]

    fun addExpenseEqual(
        description: String,
        amount: Double,
        paidBy: User,
        participants: List<User>,
        groupId: String? = null
    ): SharingResult = addExpense(description, amount, paidBy, SplitMethod.EQUAL, participants, emptyMap(), groupId)

    fun addExpenseExact(
        description: String,
        amount: Double,
        paidBy: User,
        exactAmounts: Map<User, Double>,
        groupId: String? = null
    ): SharingResult = addExpense(description, amount, paidBy, SplitMethod.EXACT, exactAmounts.keys.toList(), exactAmounts, groupId)

    fun addExpensePercentage(
        description: String,
        amount: Double,
        paidBy: User,
        percentages: Map<User, Double>,
        groupId: String? = null
    ): SharingResult = addExpense(description, amount, paidBy, SplitMethod.PERCENTAGE, percentages.keys.toList(), percentages, groupId)

    fun addExpenseByShares(
        description: String,
        amount: Double,
        paidBy: User,
        shares: Map<User, Double>,
        groupId: String? = null
    ): SharingResult = addExpense(description, amount, paidBy, SplitMethod.SHARES, shares.keys.toList(), shares, groupId)

    fun addExpense(
        description: String,
        amount: Double,
        paidBy: User,
        method: SplitMethod,
        participants: List<User>,
        metadata: Map<User, Double> = emptyMap(),
        groupId: String? = null
    ): SharingResult {
        if (amount <= 0) return SharingResult.ValidationError("Amount must be positive")
        if (participants.isEmpty()) return SharingResult.ValidationError("Must have at least one participant")

        val calculator = calculators[method]
            ?: return SharingResult.ValidationError("Unknown split method: $method")

        val splitDetails: Map<User, Double>
        try {
            splitDetails = calculator.calculate(amount, participants, metadata)
        } catch (e: IllegalArgumentException) {
            return SharingResult.ValidationError(e.message ?: "Invalid split parameters")
        }

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

        splitDetails.forEach { (user, share) ->
            if (user != paidBy) {
                graph.addEdge(from = user, to = paidBy, amount = share)
            }
        }

        return SharingResult.Success(expense)
    }

    fun recordPayment(from: User, to: User, amount: Double): Boolean {
        if (amount <= 0 || from == to) return false
        val currentDebt = graph.getDebtFrom(from, to)
        if (currentDebt <= 0.01) return false

        graph.removeEdge(from, to, amount)
        if (amount > currentDebt + 0.01) {
            graph.addEdge(to, from, roundTwo(amount - currentDebt))
        }
        return true
    }

    fun simplifyDebts(): SettlementResult {
        val settlements = graph.simplify()
        return if (settlements.isEmpty()) {
            SettlementResult.NoSettlementsNeeded()
        } else {
            SettlementResult.Success(settlements)
        }
    }

    fun getNetBalance(user: User): Double {
        val balances = graph.getNetBalances()
        return balances[user] ?: 0.0
    }

    fun getDebtsOf(user: User): Map<User, Double> = graph.getDebtsOf(user)

    fun getCreditsOf(user: User): Map<User, Double> = graph.getCreditsOf(user)

    fun getAllEdges(): List<DebtEdge> = graph.getEdges()

    fun getExpenses(): List<SharedExpense> = expenses.toList()

    fun getExpensesByGroup(groupId: String): List<SharedExpense> =
        expenses.filter { it.groupId == groupId }

    fun getExpensesByUser(user: User): List<SharedExpense> =
        expenses.filter { it.paidBy == user || it.participants.contains(user) }

    fun getTotalExpenses(): Double = expenses.sumOf { it.totalAmount }
}
