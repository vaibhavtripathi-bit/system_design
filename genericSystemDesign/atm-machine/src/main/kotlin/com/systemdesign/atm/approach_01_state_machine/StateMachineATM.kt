package com.systemdesign.atm.approach_01_state_machine

import com.systemdesign.atm.common.*
import java.util.concurrent.CopyOnWriteArrayList

class StateMachineATM(
    private val bankingService: BankingService,
    cassettes: List<CashCassette>,
    private val maxPinAttempts: Int = 3
) {
    private var state = ATMState()
    private var currentAccount: Account? = null
    private val cashCassettes = cassettes.toMutableList()
    private val observers = CopyOnWriteArrayList<ATMObserver>()

    fun getState(): ATMState = state

    fun insertCard(card: Card): Boolean {
        if (state.sessionState != SessionState.IDLE) return false
        if (!bankingService.validateCard(card)) return false
        updateState(state.copy(
            cardState = CardState.INSERTED,
            sessionState = SessionState.CARD_INSERTED,
            currentCard = card
        ))
        return true
    }

    fun enterPin(pin: String): Boolean {
        if (state.sessionState != SessionState.CARD_INSERTED) return false
        val card = state.currentCard ?: return false
        val account = bankingService.validatePin(card.cardNumber, pin)
        
        return if (account != null) {
            currentAccount = account
            updateState(state.copy(
                cardState = CardState.VALIDATED,
                sessionState = SessionState.PIN_ENTERED,
                pinAttempts = 0
            ))
            true
        } else {
            val attempts = state.pinAttempts + 1
            if (attempts >= maxPinAttempts) {
                updateState(state.copy(cardState = CardState.SWALLOWED, sessionState = SessionState.CANCELLED, pinAttempts = attempts))
                notifyError("Card swallowed after $maxPinAttempts failed attempts")
            } else {
                updateState(state.copy(pinAttempts = attempts))
                notifyError("Invalid PIN. ${maxPinAttempts - attempts} attempts remaining")
            }
            false
        }
    }

    fun selectTransaction(type: TransactionType, amount: Double = 0.0): Boolean {
        if (state.sessionState != SessionState.PIN_ENTERED) return false
        val account = currentAccount ?: return false
        val transaction = Transaction(type = type, amount = amount, accountNumber = account.accountNumber)
        updateState(state.copy(sessionState = SessionState.TRANSACTION_SELECTED, currentTransaction = transaction))
        return true
    }

    fun processTransaction(): Transaction? {
        if (state.sessionState != SessionState.TRANSACTION_SELECTED) return null
        val account = currentAccount ?: return null
        var transaction = state.currentTransaction ?: return null
        updateState(state.copy(sessionState = SessionState.PROCESSING))

        val success = when (transaction.type) {
            TransactionType.WITHDRAWAL -> {
                if (canDispense(transaction.amount.toInt()) && bankingService.withdraw(account, transaction.amount)) {
                    dispense(transaction.amount.toInt())
                    true
                } else false
            }
            TransactionType.DEPOSIT -> bankingService.deposit(account, transaction.amount)
            TransactionType.BALANCE_INQUIRY -> true
            TransactionType.TRANSFER -> bankingService.transfer(account, "", transaction.amount)
        }

        transaction = transaction.copy(status = if (success) TransactionStatus.SUCCESS else TransactionStatus.FAILED)
        updateState(state.copy(sessionState = SessionState.COMPLETE, currentTransaction = transaction))
        notifyTransaction(transaction)
        return transaction
    }

    fun ejectCard() {
        if (state.cardState == CardState.SWALLOWED) return
        reset()
    }

    fun cancel() {
        val transaction = state.currentTransaction?.copy(status = TransactionStatus.CANCELLED)
        if (transaction != null) notifyTransaction(transaction)
        reset()
    }

    private fun canDispense(amount: Int): Boolean {
        var remaining = amount
        for (cassette in cashCassettes.sortedByDescending { it.denomination }) {
            val canDispense = (remaining / cassette.denomination).coerceAtMost(cassette.count)
            remaining -= canDispense * cassette.denomination
        }
        return remaining == 0
    }

    private fun dispense(amount: Int): Int {
        var remaining = amount
        var dispensed = 0
        for (cassette in cashCassettes.sortedByDescending { it.denomination }) {
            val toDispense = (remaining / cassette.denomination).coerceAtMost(cassette.count)
            cassette.count -= toDispense
            dispensed += toDispense * cassette.denomination
            remaining -= toDispense * cassette.denomination
        }
        return dispensed
    }

    private fun reset() {
        currentAccount = null
        updateState(ATMState())
    }

    private fun updateState(newState: ATMState) {
        val old = state
        state = newState
        observers.forEach { it.onStateChange(old, newState) }
    }

    private fun notifyTransaction(transaction: Transaction) {
        observers.forEach { it.onTransaction(transaction) }
    }

    private fun notifyError(message: String) {
        observers.forEach { it.onError(message) }
    }

    fun addObserver(observer: ATMObserver) { observers.add(observer) }
    fun removeObserver(observer: ATMObserver) { observers.remove(observer) }
    fun getCashBalance(): Map<Int, Int> = cashCassettes.associate { it.denomination to it.count }
}
