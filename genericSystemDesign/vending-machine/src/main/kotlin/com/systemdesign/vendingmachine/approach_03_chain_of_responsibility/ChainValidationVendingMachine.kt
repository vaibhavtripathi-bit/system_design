package com.systemdesign.vendingmachine.approach_03_chain_of_responsibility

import com.systemdesign.vendingmachine.common.*

/**
 * Approach 3: Chain of Responsibility for Validation
 * 
 * Payment and product validation is handled through a chain of validators.
 * Each validator in the chain handles a specific concern.
 * 
 * Pattern: Chain of Responsibility
 * 
 * Trade-offs:
 * + Validation concerns are separated and independent
 * + Easy to add/remove validation steps
 * + Validation order can be configured
 * - Chain must be properly terminated
 * - Debugging requires following the chain
 * 
 * When to use:
 * - When multiple validation steps are needed
 * - When validation rules may change or be added
 * - When different validation chains are needed for different scenarios
 * 
 * Extensibility:
 * - New validation: Create new ValidationHandler implementation
 * - Different validation chains: Compose handlers differently
 */

/** Validation result */
sealed class ValidationResult {
    data object Valid : ValidationResult()
    data class Invalid(val reason: String, val code: String) : ValidationResult()
}

/** Validation context holding all data needed for validation */
data class ValidationContext(
    val payment: Payment?,
    val product: Product?,
    val slot: Slot?,
    val currentBalance: Double,
    val cashInventory: CashInventory?
)

/** Abstract validation handler */
abstract class ValidationHandler {
    protected var next: ValidationHandler? = null
    
    fun setNext(handler: ValidationHandler): ValidationHandler {
        next = handler
        return handler
    }
    
    fun validate(context: ValidationContext): ValidationResult {
        val result = doValidate(context)
        if (result is ValidationResult.Invalid) {
            return result
        }
        return next?.validate(context) ?: ValidationResult.Valid
    }
    
    protected abstract fun doValidate(context: ValidationContext): ValidationResult
}

/** Validates that payment amount is positive */
class PaymentAmountValidator : ValidationHandler() {
    override fun doValidate(context: ValidationContext): ValidationResult {
        val payment = context.payment ?: return ValidationResult.Valid
        
        return if (payment.amount > 0) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid("Payment amount must be positive", "INVALID_AMOUNT")
        }
    }
}

/** Validates coin/note denomination */
class DenominationValidator : ValidationHandler() {
    override fun doValidate(context: ValidationContext): ValidationResult {
        val payment = context.payment ?: return ValidationResult.Valid
        
        return when (payment) {
            is Payment.CoinPayment -> {
                if (payment.coin in CoinType.entries) {
                    ValidationResult.Valid
                } else {
                    ValidationResult.Invalid("Invalid coin denomination", "INVALID_COIN")
                }
            }
            is Payment.NotePayment -> {
                if (payment.note in NoteType.entries) {
                    ValidationResult.Valid
                } else {
                    ValidationResult.Invalid("Invalid note denomination", "INVALID_NOTE")
                }
            }
            else -> ValidationResult.Valid
        }
    }
}

/** Validates product is in stock */
class StockValidator : ValidationHandler() {
    override fun doValidate(context: ValidationContext): ValidationResult {
        val slot = context.slot ?: return ValidationResult.Valid
        
        return if (!slot.isEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid("Product out of stock", "OUT_OF_STOCK")
        }
    }
}

/** Validates product is not expired */
class ExpirationValidator : ValidationHandler() {
    override fun doValidate(context: ValidationContext): ValidationResult {
        val product = context.product ?: return ValidationResult.Valid
        
        return if (!product.isExpired()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid("Product has expired", "EXPIRED")
        }
    }
}

/** Validates sufficient balance for purchase */
class BalanceValidator : ValidationHandler() {
    override fun doValidate(context: ValidationContext): ValidationResult {
        val product = context.product ?: return ValidationResult.Valid
        
        return if (context.currentBalance >= product.price) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(
                "Insufficient balance: need ${product.price}, have ${context.currentBalance}",
                "INSUFFICIENT_BALANCE"
            )
        }
    }
}

/** Validates change can be provided */
class ChangeAvailabilityValidator : ValidationHandler() {
    override fun doValidate(context: ValidationContext): ValidationResult {
        val product = context.product ?: return ValidationResult.Valid
        val inventory = context.cashInventory ?: return ValidationResult.Valid
        
        val changeNeeded = context.currentBalance - product.price
        if (changeNeeded <= 0) return ValidationResult.Valid
        
        val canProvideChange = checkChangeAvailability(changeNeeded, inventory)
        
        return if (canProvideChange) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid("Cannot provide exact change", "NO_CHANGE")
        }
    }
    
    private fun checkChangeAvailability(amount: Double, inventory: CashInventory): Boolean {
        var remaining = (amount * 100).toInt()
        val tempInventory = inventory.coins.toMutableMap()
        
        for (coin in CoinType.entries.sortedByDescending { it.value }) {
            val coinValue = (coin.value * 100).toInt()
            val available = tempInventory[coin] ?: 0
            val needed = remaining / coinValue
            val used = minOf(needed, available)
            remaining -= used * coinValue
            tempInventory[coin] = available - used
        }
        
        return remaining == 0
    }
}

/** Builder for validation chains */
class ValidationChainBuilder {
    private var head: ValidationHandler? = null
    private var tail: ValidationHandler? = null
    
    fun add(handler: ValidationHandler): ValidationChainBuilder {
        if (head == null) {
            head = handler
            tail = handler
        } else {
            tail?.setNext(handler)
            tail = handler
        }
        return this
    }
    
    fun build(): ValidationHandler {
        return head ?: throw IllegalStateException("Chain is empty")
    }
    
    companion object {
        fun purchaseChain(): ValidationHandler {
            return ValidationChainBuilder()
                .add(StockValidator())
                .add(ExpirationValidator())
                .add(BalanceValidator())
                .add(ChangeAvailabilityValidator())
                .build()
        }
        
        fun paymentChain(): ValidationHandler {
            return ValidationChainBuilder()
                .add(PaymentAmountValidator())
                .add(DenominationValidator())
                .build()
        }
    }
}

/**
 * Vending machine using chain of responsibility for validation
 */
class ChainValidatedVendingMachine(
    private val paymentChain: ValidationHandler = ValidationChainBuilder.paymentChain(),
    private val purchaseChain: ValidationHandler = ValidationChainBuilder.purchaseChain()
) {
    private val slots = mutableMapOf<String, Slot>()
    private val cashInventory = CashInventory()
    private var currentBalance = 0.0
    
    fun addSlot(slot: Slot) {
        slots[slot.code] = slot
    }
    
    fun insertPayment(payment: Payment): ValidationResult {
        val context = ValidationContext(
            payment = payment,
            product = null,
            slot = null,
            currentBalance = currentBalance,
            cashInventory = cashInventory
        )
        
        val result = paymentChain.validate(context)
        
        if (result is ValidationResult.Valid) {
            currentBalance += payment.amount
            when (payment) {
                is Payment.CoinPayment -> cashInventory.addCoin(payment.coin)
                is Payment.NotePayment -> cashInventory.addNote(payment.note)
                else -> {}
            }
        }
        
        return result
    }
    
    fun selectProduct(slotCode: String): ValidationResult {
        val slot = slots[slotCode] ?: return ValidationResult.Invalid(
            "Invalid slot code",
            "INVALID_SLOT"
        )
        
        val context = ValidationContext(
            payment = null,
            product = slot.product,
            slot = slot,
            currentBalance = currentBalance,
            cashInventory = cashInventory
        )
        
        return purchaseChain.validate(context)
    }
    
    fun getBalance(): Double = currentBalance
    
    fun refillCash(coins: Map<CoinType, Int>) {
        coins.forEach { (coin, count) ->
            cashInventory.addCoin(coin, count)
        }
    }
}
