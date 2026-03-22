package com.systemdesign.expensetracker.common

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
        if (!members.contains(user)) {
            members.add(user)
        }
    }
    
    fun removeMember(user: User) {
        members.remove(user)
    }
}

data class Split(
    val user: User,
    val amount: Double
) {
    init {
        require(amount >= 0) { "Split amount cannot be negative" }
    }
}

data class Expense(
    val id: String = UUID.randomUUID().toString(),
    val description: String,
    val amount: Double,
    val paidBy: User,
    val splits: List<Split>,
    val category: String = "General",
    val timestamp: LocalDateTime = LocalDateTime.now(),
    val groupId: String? = null
) {
    init {
        require(amount > 0) { "Expense amount must be positive" }
        require(splits.isNotEmpty()) { "Expense must have at least one split" }
    }
    
    fun validateSplits(): Boolean {
        val totalSplit = splits.sumOf { it.amount }
        return kotlin.math.abs(totalSplit - amount) <= 0.02
    }
}

data class Payment(
    val id: String = UUID.randomUUID().toString(),
    val from: User,
    val to: User,
    val amount: Double,
    val timestamp: LocalDateTime = LocalDateTime.now(),
    val note: String = ""
) {
    init {
        require(amount > 0) { "Payment amount must be positive" }
        require(from != to) { "Cannot make payment to self" }
    }
}

data class Balance(
    val user: User,
    val owes: Map<User, Double> = emptyMap(),
    val owed: Map<User, Double> = emptyMap()
) {
    val netBalance: Double
        get() = owed.values.sum() - owes.values.sum()
    
    val totalOwes: Double
        get() = owes.values.sum()
    
    val totalOwed: Double
        get() = owed.values.sum()
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

enum class SplitType {
    EQUAL,
    EXACT,
    PERCENTAGE,
    SHARE_BASED
}

sealed class ExpenseResult {
    data class Success(val expense: Expense) : ExpenseResult()
    data class ValidationError(val message: String) : ExpenseResult()
    data class InsufficientData(val message: String) : ExpenseResult()
}

sealed class PaymentResult {
    data class Success(val payment: Payment) : PaymentResult()
    data class ValidationError(val message: String) : PaymentResult()
    data class NoDebtExists(val from: User, val to: User) : PaymentResult()
}

sealed class SettlementResult {
    data class Success(val settlements: List<Settlement>) : SettlementResult()
    data class NoSettlementsNeeded(val message: String = "All balances are settled") : SettlementResult()
}
