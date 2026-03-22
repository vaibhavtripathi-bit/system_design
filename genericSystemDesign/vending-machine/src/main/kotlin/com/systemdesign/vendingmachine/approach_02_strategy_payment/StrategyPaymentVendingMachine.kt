package com.systemdesign.vendingmachine.approach_02_strategy_payment

import com.systemdesign.vendingmachine.common.*

/**
 * Approach 2: Strategy Pattern for Payment Processing
 * 
 * Different payment methods are handled by interchangeable strategies.
 * Easily add new payment methods without changing core logic.
 * 
 * Pattern: Strategy Pattern
 * 
 * Trade-offs:
 * + Payment methods are completely decoupled
 * + Easy to add new payment methods (QR, NFC, etc.)
 * + Payment processing testable in isolation
 * - Need to manage multiple strategy instances
 * - Payment validation logic distributed across strategies
 * 
 * When to use:
 * - When multiple payment methods need to be supported
 * - When payment methods may be added/removed at runtime
 * - When different validation rules apply to different payments
 * 
 * Extensibility:
 * - New payment method: Implement PaymentStrategy interface
 * - Payment validation: Add to strategy's validate method
 */

/** Payment strategy interface */
interface PaymentStrategy {
    fun validate(payment: Payment): PaymentValidationResult
    fun process(payment: Payment): PaymentProcessResult
    fun refund(payment: Payment, amount: Double): RefundResult
}

sealed class PaymentValidationResult {
    data object Valid : PaymentValidationResult()
    data class Invalid(val reason: String) : PaymentValidationResult()
}

sealed class PaymentProcessResult {
    data class Success(val transactionId: String) : PaymentProcessResult()
    data class Failure(val reason: String) : PaymentProcessResult()
}

sealed class RefundResult {
    data class Success(val refundId: String) : RefundResult()
    data class Failure(val reason: String) : RefundResult()
}

/** Cash payment strategy */
class CashPaymentStrategy(
    private val cashInventory: CashInventory
) : PaymentStrategy {
    
    override fun validate(payment: Payment): PaymentValidationResult {
        return when (payment) {
            is Payment.CoinPayment -> PaymentValidationResult.Valid
            is Payment.NotePayment -> PaymentValidationResult.Valid
            else -> PaymentValidationResult.Invalid("Not a cash payment")
        }
    }
    
    override fun process(payment: Payment): PaymentProcessResult {
        return when (payment) {
            is Payment.CoinPayment -> {
                cashInventory.addCoin(payment.coin)
                PaymentProcessResult.Success("CASH-${System.currentTimeMillis()}")
            }
            is Payment.NotePayment -> {
                cashInventory.addNote(payment.note)
                PaymentProcessResult.Success("CASH-${System.currentTimeMillis()}")
            }
            else -> PaymentProcessResult.Failure("Not a cash payment")
        }
    }
    
    override fun refund(payment: Payment, amount: Double): RefundResult {
        // For cash, refund is handled by change mechanism
        return RefundResult.Success("REFUND-${System.currentTimeMillis()}")
    }
}

/** Card payment strategy */
class CardPaymentStrategy(
    private val cardProcessor: CardProcessor = MockCardProcessor()
) : PaymentStrategy {
    
    override fun validate(payment: Payment): PaymentValidationResult {
        if (payment !is Payment.CardPayment) {
            return PaymentValidationResult.Invalid("Not a card payment")
        }
        
        // Validate card number format (simplified)
        if (payment.cardNumber.length < 13) {
            return PaymentValidationResult.Invalid("Invalid card number")
        }
        
        return PaymentValidationResult.Valid
    }
    
    override fun process(payment: Payment): PaymentProcessResult {
        if (payment !is Payment.CardPayment) {
            return PaymentProcessResult.Failure("Not a card payment")
        }
        
        return cardProcessor.charge(payment.cardNumber, payment.amount)
    }
    
    override fun refund(payment: Payment, amount: Double): RefundResult {
        if (payment !is Payment.CardPayment) {
            return RefundResult.Failure("Not a card payment")
        }
        
        return cardProcessor.refund(payment.cardNumber, amount)
    }
}

/** Card processor interface */
interface CardProcessor {
    fun charge(cardNumber: String, amount: Double): PaymentProcessResult
    fun refund(cardNumber: String, amount: Double): RefundResult
}

/** Mock card processor for testing */
class MockCardProcessor : CardProcessor {
    var shouldFail = false
    
    override fun charge(cardNumber: String, amount: Double): PaymentProcessResult {
        return if (shouldFail) {
            PaymentProcessResult.Failure("Card declined")
        } else {
            PaymentProcessResult.Success("CARD-${System.currentTimeMillis()}")
        }
    }
    
    override fun refund(cardNumber: String, amount: Double): RefundResult {
        return if (shouldFail) {
            RefundResult.Failure("Refund failed")
        } else {
            RefundResult.Success("REFUND-${System.currentTimeMillis()}")
        }
    }
}

/** Mobile payment strategy (QR code, NFC, etc.) */
class MobilePaymentStrategy(
    private val mobileProcessor: MobileProcessor = MockMobileProcessor()
) : PaymentStrategy {
    
    override fun validate(payment: Payment): PaymentValidationResult {
        if (payment !is Payment.MobilePayment) {
            return PaymentValidationResult.Invalid("Not a mobile payment")
        }
        
        return PaymentValidationResult.Valid
    }
    
    override fun process(payment: Payment): PaymentProcessResult {
        if (payment !is Payment.MobilePayment) {
            return PaymentProcessResult.Failure("Not a mobile payment")
        }
        
        return mobileProcessor.processPayment(payment.transactionId, payment.amount)
    }
    
    override fun refund(payment: Payment, amount: Double): RefundResult {
        if (payment !is Payment.MobilePayment) {
            return RefundResult.Failure("Not a mobile payment")
        }
        
        return mobileProcessor.refund(payment.transactionId, amount)
    }
}

/** Mobile processor interface */
interface MobileProcessor {
    fun processPayment(transactionId: String, amount: Double): PaymentProcessResult
    fun refund(transactionId: String, amount: Double): RefundResult
}

/** Mock mobile processor for testing */
class MockMobileProcessor : MobileProcessor {
    var shouldFail = false
    
    override fun processPayment(transactionId: String, amount: Double): PaymentProcessResult {
        return if (shouldFail) {
            PaymentProcessResult.Failure("Mobile payment failed")
        } else {
            PaymentProcessResult.Success("MOBILE-${System.currentTimeMillis()}")
        }
    }
    
    override fun refund(transactionId: String, amount: Double): RefundResult {
        return if (shouldFail) {
            RefundResult.Failure("Refund failed")
        } else {
            RefundResult.Success("REFUND-${System.currentTimeMillis()}")
        }
    }
}

/** Payment processor that routes to appropriate strategy */
class StrategyBasedPaymentProcessor(
    private val strategies: Map<String, PaymentStrategy> = emptyMap()
) {
    private val defaultStrategies = mutableMapOf<String, PaymentStrategy>()
    
    init {
        strategies.forEach { (key, strategy) ->
            defaultStrategies[key] = strategy
        }
    }
    
    fun registerStrategy(paymentType: String, strategy: PaymentStrategy) {
        defaultStrategies[paymentType] = strategy
    }
    
    fun processPayment(payment: Payment): PaymentProcessResult {
        val strategy = getStrategyForPayment(payment)
            ?: return PaymentProcessResult.Failure("No strategy for payment type")
        
        val validation = strategy.validate(payment)
        if (validation is PaymentValidationResult.Invalid) {
            return PaymentProcessResult.Failure(validation.reason)
        }
        
        return strategy.process(payment)
    }
    
    fun refundPayment(payment: Payment, amount: Double): RefundResult {
        val strategy = getStrategyForPayment(payment)
            ?: return RefundResult.Failure("No strategy for payment type")
        
        return strategy.refund(payment, amount)
    }
    
    private fun getStrategyForPayment(payment: Payment): PaymentStrategy? {
        return when (payment) {
            is Payment.CoinPayment, is Payment.NotePayment -> defaultStrategies["cash"]
            is Payment.CardPayment -> defaultStrategies["card"]
            is Payment.MobilePayment -> defaultStrategies["mobile"]
        }
    }
}
