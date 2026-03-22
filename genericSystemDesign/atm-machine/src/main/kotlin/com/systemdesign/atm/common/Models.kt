package com.systemdesign.atm.common

import java.time.LocalDateTime
import java.util.UUID

enum class CardState { NONE, INSERTED, VALIDATED, SWALLOWED }
enum class SessionState { IDLE, CARD_INSERTED, PIN_ENTERED, TRANSACTION_SELECTED, PROCESSING, COMPLETE, CANCELLED }
enum class TransactionType { WITHDRAWAL, DEPOSIT, BALANCE_INQUIRY, TRANSFER }
enum class TransactionStatus { PENDING, SUCCESS, FAILED, CANCELLED }

data class Card(val cardNumber: String, val expiryDate: String, val bankCode: String)
data class Account(val accountNumber: String, val balance: Double, val pin: String)
data class CashCassette(val denomination: Int, var count: Int) {
    fun dispense(amount: Int): Int {
        val needed = amount / denomination
        val available = minOf(needed, count)
        count -= available
        return available * denomination
    }
}

data class Transaction(
    val id: String = UUID.randomUUID().toString(),
    val type: TransactionType,
    val amount: Double,
    val accountNumber: String,
    val status: TransactionStatus = TransactionStatus.PENDING,
    val timestamp: LocalDateTime = LocalDateTime.now()
)

data class ATMState(
    val cardState: CardState = CardState.NONE,
    val sessionState: SessionState = SessionState.IDLE,
    val currentCard: Card? = null,
    val pinAttempts: Int = 0,
    val currentTransaction: Transaction? = null
)

interface BankingService {
    fun validateCard(card: Card): Boolean
    fun validatePin(cardNumber: String, pin: String): Account?
    fun withdraw(account: Account, amount: Double): Boolean
    fun deposit(account: Account, amount: Double): Boolean
    fun getBalance(account: Account): Double
    fun transfer(from: Account, toAccountNumber: String, amount: Double): Boolean
}

interface ATMObserver {
    fun onStateChange(oldState: ATMState, newState: ATMState)
    fun onTransaction(transaction: Transaction)
    fun onError(message: String)
}
