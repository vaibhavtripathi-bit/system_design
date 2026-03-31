package com.systemdesign.expensesharing.common

import java.time.LocalDateTime
import java.util.UUID

data class User(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val email: String
) {
    override fun equals(other: Any?): Boolean = other is User && id == other.id
    override fun hashCode(): Int = id.hashCode()
}

data class Group(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val members: MutableList<User> = mutableListOf()
) {
    fun addMember(user: User) {
        if (!members.contains(user)) members.add(user)
    }

    fun removeMember(user: User) {
        members.remove(user)
    }
}

enum class SplitMethod {
    EQUAL,
    EXACT,
    PERCENTAGE,
    SHARES
}

data class SharedExpense(
    val id: String = UUID.randomUUID().toString(),
    val description: String,
    val totalAmount: Double,
    val paidBy: User,
    val splitMethod: SplitMethod,
    val participants: List<User>,
    val splitDetails: Map<User, Double>,
    val groupId: String? = null,
    val timestamp: LocalDateTime = LocalDateTime.now()
) {
    init {
        require(totalAmount > 0) { "Expense amount must be positive" }
        require(participants.isNotEmpty()) { "Must have at least one participant" }
    }

    fun validateSplits(): Boolean {
        val total = splitDetails.values.sum()
        return kotlin.math.abs(total - totalAmount) <= 0.02
    }
}

data class DebtEdge(
    val from: User,
    val to: User,
    val amount: Double
) {
    init {
        require(amount > 0) { "Debt amount must be positive" }
        require(from != to) { "Cannot owe money to yourself" }
    }
}

data class Settlement(
    val from: User,
    val to: User,
    val amount: Double
) {
    init {
        require(amount > 0) { "Settlement amount must be positive" }
    }
}

sealed class SharingResult {
    data class Success(val expense: SharedExpense) : SharingResult()
    data class ValidationError(val message: String) : SharingResult()
}

sealed class SettlementResult {
    data class Success(val settlements: List<Settlement>) : SettlementResult()
    data class NoSettlementsNeeded(val message: String = "All balances are settled") : SettlementResult()
}

interface ExpenseSharingObserver {
    fun onExpenseAdded(expense: SharedExpense)
    fun onSettlementGenerated(settlements: List<Settlement>)
    fun onBalanceChanged(user: User, netBalance: Double)
    fun onMemberJoined(group: Group, user: User)
}
