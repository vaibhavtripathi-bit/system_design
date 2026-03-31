package com.systemdesign.atm.approach_03_template_method

import com.systemdesign.atm.common.*
import java.time.LocalDateTime
import java.util.UUID

/**
 * Approach 3: Template Method for Transaction Processing
 *
 * Abstract base class defines the skeleton algorithm for any ATM transaction:
 * authenticate → selectTransaction → validate → process → dispense → printReceipt.
 * Concrete subclasses override only the steps that differ per transaction type.
 *
 * Pattern: Template Method
 *
 * Trade-offs:
 * + Adding a new transaction type requires only subclassing and overriding hooks
 * + The invariant ordering (auth before validate, validate before process) is enforced once
 * + Cross-cutting concerns (logging, receipt) live in the base class
 * - Inheritance hierarchy can become rigid if steps diverge significantly
 * - Hook granularity must be decided up-front; changing the skeleton is a breaking change
 *
 * When to use:
 * - When all transactions share the same high-level flow but differ in details
 * - When step ordering must be enforced across transaction types
 * - When new transaction types are added frequently
 *
 * Extensibility:
 * - New transaction type: Subclass ATMTransactionTemplate, override validate/process/dispense
 * - New receipt format: Override formatReceipt
 */

data class TransactionContext(
    val account: Account,
    val amount: Double = 0.0,
    val targetAccountNumber: String = "",
    val depositEnvelopeId: String = ""
)

data class TransactionReceipt(
    val transactionId: String,
    val type: TransactionType,
    val amount: Double,
    val accountNumber: String,
    val status: TransactionStatus,
    val balanceAfter: Double,
    val timestamp: LocalDateTime,
    val details: Map<String, String> = emptyMap()
) {
    fun format(): String {
        val lines = mutableListOf(
            "========== ATM RECEIPT ==========",
            "Transaction: $transactionId",
            "Type:        $type",
            "Amount:      $${"%.2f".format(amount)}",
            "Account:     ***${accountNumber.takeLast(4)}",
            "Status:      $status",
            "Balance:     $${"%.2f".format(balanceAfter)}",
            "Date:        $timestamp"
        )
        details.forEach { (k, v) -> lines.add("$k: $v") }
        lines.add("=================================")
        return lines.joinToString("\n")
    }
}

sealed class TransactionResult {
    data class Success(val receipt: TransactionReceipt) : TransactionResult()
    data class Failure(val reason: String, val receipt: TransactionReceipt?) : TransactionResult()
}

abstract class ATMTransactionTemplate(
    protected val bankingService: BankingService,
    protected val cashCassettes: List<CashCassette>
) {
    abstract val transactionType: TransactionType

    fun execute(card: Card, pin: String, context: TransactionContext): TransactionResult {
        val account = authenticate(card, pin)
            ?: return TransactionResult.Failure("Authentication failed", null)

        val enrichedContext = context.copy(account = account)
        val validationError = validate(enrichedContext)
        if (validationError != null) {
            return TransactionResult.Failure(validationError, buildReceipt(enrichedContext, TransactionStatus.FAILED, account))
        }

        val success = process(enrichedContext)
        if (!success) {
            return TransactionResult.Failure("Transaction processing failed", buildReceipt(enrichedContext, TransactionStatus.FAILED, account))
        }

        dispense(enrichedContext)

        val receipt = buildReceipt(enrichedContext, TransactionStatus.SUCCESS, account)
        return TransactionResult.Success(receipt)
    }

    private fun authenticate(card: Card, pin: String): Account? {
        if (!bankingService.validateCard(card)) return null
        return bankingService.validatePin(card.cardNumber, pin)
    }

    protected abstract fun validate(context: TransactionContext): String?

    protected abstract fun process(context: TransactionContext): Boolean

    protected open fun dispense(context: TransactionContext) {}

    protected open fun additionalReceiptDetails(context: TransactionContext): Map<String, String> = emptyMap()

    private fun buildReceipt(context: TransactionContext, status: TransactionStatus, account: Account): TransactionReceipt {
        return TransactionReceipt(
            transactionId = UUID.randomUUID().toString().take(8).uppercase(),
            type = transactionType,
            amount = context.amount,
            accountNumber = context.account.accountNumber,
            status = status,
            balanceAfter = bankingService.getBalance(account),
            timestamp = LocalDateTime.now(),
            details = additionalReceiptDetails(context)
        )
    }
}

class WithdrawalTransaction(
    bankingService: BankingService,
    cashCassettes: List<CashCassette>
) : ATMTransactionTemplate(bankingService, cashCassettes) {

    override val transactionType = TransactionType.WITHDRAWAL

    override fun validate(context: TransactionContext): String? {
        if (context.amount <= 0) return "Amount must be positive"
        if (context.amount % 10 != 0.0) return "Amount must be a multiple of 10"
        if (context.amount > context.account.balance) return "Insufficient funds"
        if (!canDispense(context.amount.toInt())) return "ATM cannot dispense this amount"
        return null
    }

    override fun process(context: TransactionContext): Boolean {
        return bankingService.withdraw(context.account, context.amount)
    }

    override fun dispense(context: TransactionContext) {
        dispenseCash(context.amount.toInt())
    }

    override fun additionalReceiptDetails(context: TransactionContext): Map<String, String> {
        return mapOf("Dispensed" to buildDispenseBreakdown(context.amount.toInt()))
    }

    private fun canDispense(amount: Int): Boolean {
        var remaining = amount
        for (cassette in cashCassettes.sortedByDescending { it.denomination }) {
            val canUse = (remaining / cassette.denomination).coerceAtMost(cassette.count)
            remaining -= canUse * cassette.denomination
        }
        return remaining == 0
    }

    private fun dispenseCash(amount: Int) {
        var remaining = amount
        for (cassette in cashCassettes.sortedByDescending { it.denomination }) {
            val toDispense = (remaining / cassette.denomination).coerceAtMost(cassette.count)
            cassette.count -= toDispense
            remaining -= toDispense * cassette.denomination
        }
    }

    private fun buildDispenseBreakdown(amount: Int): String {
        val parts = mutableListOf<String>()
        var remaining = amount
        for (cassette in cashCassettes.sortedByDescending { it.denomination }) {
            val count = (remaining / cassette.denomination).coerceAtMost(cassette.count)
            if (count > 0) {
                parts.add("${count}x\$${cassette.denomination}")
                remaining -= count * cassette.denomination
            }
        }
        return parts.joinToString(", ")
    }
}

class DepositTransaction(
    bankingService: BankingService,
    cashCassettes: List<CashCassette>
) : ATMTransactionTemplate(bankingService, cashCassettes) {

    override val transactionType = TransactionType.DEPOSIT

    override fun validate(context: TransactionContext): String? {
        if (context.amount <= 0) return "Deposit amount must be positive"
        if (context.depositEnvelopeId.isBlank()) return "Deposit envelope required"
        return null
    }

    override fun process(context: TransactionContext): Boolean {
        return bankingService.deposit(context.account, context.amount)
    }

    override fun additionalReceiptDetails(context: TransactionContext): Map<String, String> {
        return mapOf("Envelope" to context.depositEnvelopeId, "Note" to "Funds subject to verification hold")
    }
}

class TransferTransaction(
    bankingService: BankingService,
    cashCassettes: List<CashCassette>
) : ATMTransactionTemplate(bankingService, cashCassettes) {

    override val transactionType = TransactionType.TRANSFER

    override fun validate(context: TransactionContext): String? {
        if (context.amount <= 0) return "Transfer amount must be positive"
        if (context.targetAccountNumber.isBlank()) return "Target account number required"
        if (context.targetAccountNumber == context.account.accountNumber) return "Cannot transfer to same account"
        if (context.amount > context.account.balance) return "Insufficient funds"
        return null
    }

    override fun process(context: TransactionContext): Boolean {
        return bankingService.transfer(context.account, context.targetAccountNumber, context.amount)
    }

    override fun additionalReceiptDetails(context: TransactionContext): Map<String, String> {
        return mapOf("To Account" to "***${context.targetAccountNumber.takeLast(4)}")
    }
}

class BalanceInquiryTransaction(
    bankingService: BankingService,
    cashCassettes: List<CashCassette>
) : ATMTransactionTemplate(bankingService, cashCassettes) {

    override val transactionType = TransactionType.BALANCE_INQUIRY

    override fun validate(context: TransactionContext): String? = null

    override fun process(context: TransactionContext): Boolean = true

    override fun additionalReceiptDetails(context: TransactionContext): Map<String, String> {
        val balance = bankingService.getBalance(context.account)
        return mapOf("Available Balance" to "$${"%.2f".format(balance)}")
    }
}

class ATMTransactionProcessor(
    private val bankingService: BankingService,
    private val cashCassettes: List<CashCassette>
) {
    private val transactionLog = mutableListOf<TransactionReceipt>()

    fun processTransaction(
        type: TransactionType,
        card: Card,
        pin: String,
        amount: Double = 0.0,
        targetAccountNumber: String = "",
        depositEnvelopeId: String = ""
    ): TransactionResult {
        val template = when (type) {
            TransactionType.WITHDRAWAL -> WithdrawalTransaction(bankingService, cashCassettes)
            TransactionType.DEPOSIT -> DepositTransaction(bankingService, cashCassettes)
            TransactionType.TRANSFER -> TransferTransaction(bankingService, cashCassettes)
            TransactionType.BALANCE_INQUIRY -> BalanceInquiryTransaction(bankingService, cashCassettes)
        }

        val placeholder = Account("", 0.0, "")
        val context = TransactionContext(
            account = placeholder,
            amount = amount,
            targetAccountNumber = targetAccountNumber,
            depositEnvelopeId = depositEnvelopeId
        )

        val result = template.execute(card, pin, context)
        when (result) {
            is TransactionResult.Success -> transactionLog.add(result.receipt)
            is TransactionResult.Failure -> result.receipt?.let { transactionLog.add(it) }
        }
        return result
    }

    fun getTransactionLog(): List<TransactionReceipt> = transactionLog.toList()
}
