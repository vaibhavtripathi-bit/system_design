package com.systemdesign.vendingmachine.common

import java.time.LocalDate

/**
 * Core domain models for Vending Machine System.
 * 
 * Extensibility Points:
 * - New payment types: Implement PaymentProcessor interface
 * - New product types: No code changes needed (data-driven)
 * - New coin denominations: Add to CoinType enum
 * 
 * Breaking Changes Required For:
 * - Changing state machine structure
 * - Adding multi-currency support (requires redesign)
 */

/** Coin denominations */
enum class CoinType(val value: Double) {
    PENNY(0.01),
    NICKEL(0.05),
    DIME(0.10),
    QUARTER(0.25),
    HALF_DOLLAR(0.50),
    DOLLAR(1.00)
}

/** Note denominations */
enum class NoteType(val value: Double) {
    ONE(1.00),
    FIVE(5.00),
    TEN(10.00),
    TWENTY(20.00)
}

/** Product in the vending machine */
data class Product(
    val code: String,
    val name: String,
    val price: Double,
    val expirationDate: LocalDate? = null
) {
    fun isExpired(): Boolean {
        return expirationDate?.isBefore(LocalDate.now()) == true
    }
}

/** A slot holding products */
data class Slot(
    val code: String,
    val product: Product?,
    var quantity: Int = 0,
    val maxCapacity: Int = 10
) {
    fun isEmpty(): Boolean = quantity <= 0
    fun isFull(): Boolean = quantity >= maxCapacity
    fun dispense(): Product? {
        if (isEmpty() || product == null) return null
        quantity--
        return product
    }
}

/** State of the vending machine */
enum class VendingMachineState {
    IDLE,
    HAS_MONEY,
    PRODUCT_SELECTED,
    DISPENSING,
    RETURNING_CHANGE,
    ADMIN_MODE,
    OUT_OF_SERVICE
}

/** Payment methods */
sealed class Payment {
    abstract val amount: Double
    
    data class CoinPayment(val coin: CoinType) : Payment() {
        override val amount: Double = coin.value
    }
    
    data class NotePayment(val note: NoteType) : Payment() {
        override val amount: Double = note.value
    }
    
    data class CardPayment(
        val cardNumber: String,
        override val amount: Double
    ) : Payment()
    
    data class MobilePayment(
        val transactionId: String,
        override val amount: Double
    ) : Payment()
}

/** Result of a vending operation */
sealed class VendingResult {
    data class Success(
        val product: Product,
        val change: List<CoinType>
    ) : VendingResult()
    
    data class InsufficientFunds(
        val required: Double,
        val inserted: Double
    ) : VendingResult()
    
    data class OutOfStock(val productCode: String) : VendingResult()
    
    data class ProductExpired(val productCode: String) : VendingResult()
    
    data class NoChangeAvailable(
        val changeRequired: Double
    ) : VendingResult()
    
    data class ProductStuck(val productCode: String) : VendingResult()
    
    data class Cancelled(val refund: List<CoinType>) : VendingResult()
    
    data class InvalidProduct(val code: String) : VendingResult()
}

/** Inventory status */
data class InventoryStatus(
    val slots: Map<String, Slot>,
    val lowStockSlots: List<String>,
    val emptySlots: List<String>,
    val expiredProducts: List<String>
)

/** Cash inventory in the machine */
data class CashInventory(
    val coins: MutableMap<CoinType, Int> = mutableMapOf(),
    val notes: MutableMap<NoteType, Int> = mutableMapOf()
) {
    fun getTotalCash(): Double {
        val coinTotal = coins.entries.sumOf { it.key.value * it.value }
        val noteTotal = notes.entries.sumOf { it.key.value * it.value }
        return coinTotal + noteTotal
    }
    
    fun addCoin(coin: CoinType, count: Int = 1) {
        coins[coin] = (coins[coin] ?: 0) + count
    }
    
    fun addNote(note: NoteType, count: Int = 1) {
        notes[note] = (notes[note] ?: 0) + count
    }
    
    fun removeCoin(coin: CoinType): Boolean {
        val current = coins[coin] ?: 0
        if (current <= 0) return false
        coins[coin] = current - 1
        return true
    }
}

/** Payment processor interface */
interface PaymentProcessor {
    fun processPayment(payment: Payment): Boolean
    fun refundPayment(payment: Payment): Boolean
}

/** Dispenser interface for handling product dispensing */
interface Dispenser {
    fun dispense(slot: Slot): DispenseResult
}

/** Result of dispensing operation */
sealed class DispenseResult {
    data class Success(val product: Product) : DispenseResult()
    data object Stuck : DispenseResult()
    data object Empty : DispenseResult()
}

/** Observer for vending machine events */
interface VendingMachineObserver {
    fun onProductDispensed(product: Product)
    fun onPaymentReceived(amount: Double)
    fun onChangeReturned(change: List<CoinType>)
    fun onSlotEmpty(slotCode: String)
    fun onError(message: String)
}
