package com.systemdesign.vendingmachine.approach_01_state_machine

import com.systemdesign.vendingmachine.common.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Approach 1: State Machine Pattern
 * 
 * The vending machine is modeled as an explicit finite state machine.
 * Each state has specific valid operations and transitions.
 * 
 * Pattern: State Machine
 * 
 * Trade-offs:
 * + Clear state transitions prevent invalid operations
 * + Easy to reason about system behavior
 * + State-specific error handling
 * - More boilerplate for state management
 * - State explosion if too many orthogonal concerns
 * 
 * When to use:
 * - When system has clear discrete states
 * - When operations are only valid in certain states
 * - When audit/logging of state transitions is important
 * 
 * Extensibility:
 * - New state: Add to enum and update transition table
 * - New payment type: Add handler in HAS_MONEY state
 */
class StateMachineVendingMachine(
    private val dispenser: Dispenser = DefaultDispenser(),
    private val initialInventory: Map<String, Slot> = emptyMap(),
    private val initialCash: CashInventory = CashInventory()
) {
    private var state: VendingMachineState = VendingMachineState.IDLE
    private var currentBalance: Double = 0.0
    private var selectedSlot: String? = null
    private val insertedPayments = mutableListOf<Payment>()
    
    private val slots = initialInventory.toMutableMap()
    private val cashInventory = initialCash
    private val observers = CopyOnWriteArrayList<VendingMachineObserver>()
    
    private val validTransitions = mapOf(
        VendingMachineState.IDLE to setOf(
            VendingMachineState.HAS_MONEY,
            VendingMachineState.ADMIN_MODE,
            VendingMachineState.OUT_OF_SERVICE
        ),
        VendingMachineState.HAS_MONEY to setOf(
            VendingMachineState.HAS_MONEY,
            VendingMachineState.PRODUCT_SELECTED,
            VendingMachineState.RETURNING_CHANGE,
            VendingMachineState.OUT_OF_SERVICE
        ),
        VendingMachineState.PRODUCT_SELECTED to setOf(
            VendingMachineState.DISPENSING,
            VendingMachineState.RETURNING_CHANGE,
            VendingMachineState.HAS_MONEY,
            VendingMachineState.OUT_OF_SERVICE
        ),
        VendingMachineState.DISPENSING to setOf(
            VendingMachineState.RETURNING_CHANGE,
            VendingMachineState.IDLE,
            VendingMachineState.OUT_OF_SERVICE
        ),
        VendingMachineState.RETURNING_CHANGE to setOf(
            VendingMachineState.IDLE,
            VendingMachineState.OUT_OF_SERVICE
        ),
        VendingMachineState.ADMIN_MODE to setOf(
            VendingMachineState.IDLE
        ),
        VendingMachineState.OUT_OF_SERVICE to setOf(
            VendingMachineState.ADMIN_MODE
        )
    )
    
    fun getState(): VendingMachineState = state
    fun getBalance(): Double = currentBalance
    fun getSelectedSlot(): String? = selectedSlot
    
    private fun canTransition(to: VendingMachineState): Boolean {
        return validTransitions[state]?.contains(to) == true
    }
    
    private fun transition(to: VendingMachineState): Boolean {
        if (!canTransition(to)) return false
        state = to
        return true
    }
    
    fun insertPayment(payment: Payment): Boolean {
        if (state == VendingMachineState.OUT_OF_SERVICE) return false
        if (state == VendingMachineState.ADMIN_MODE) return false
        
        if (state == VendingMachineState.IDLE) {
            transition(VendingMachineState.HAS_MONEY)
        }
        
        if (state != VendingMachineState.HAS_MONEY) return false
        
        when (payment) {
            is Payment.CoinPayment -> {
                cashInventory.addCoin(payment.coin)
            }
            is Payment.NotePayment -> {
                cashInventory.addNote(payment.note)
            }
            else -> {}
        }
        
        currentBalance += payment.amount
        insertedPayments.add(payment)
        notifyPaymentReceived(payment.amount)
        
        return true
    }
    
    fun selectProduct(slotCode: String): VendingResult {
        if (state != VendingMachineState.HAS_MONEY && state != VendingMachineState.IDLE) {
            return VendingResult.InvalidProduct(slotCode)
        }
        
        val slot = slots[slotCode] ?: return VendingResult.InvalidProduct(slotCode)
        val product = slot.product ?: return VendingResult.InvalidProduct(slotCode)
        
        if (slot.isEmpty()) {
            return VendingResult.OutOfStock(slotCode)
        }
        
        if (product.isExpired()) {
            return VendingResult.ProductExpired(slotCode)
        }
        
        if (currentBalance < product.price) {
            return VendingResult.InsufficientFunds(product.price, currentBalance)
        }
        
        selectedSlot = slotCode
        transition(VendingMachineState.PRODUCT_SELECTED)
        
        return dispenseProduct()
    }
    
    private fun dispenseProduct(): VendingResult {
        val slotCode = selectedSlot ?: return VendingResult.InvalidProduct("")
        val slot = slots[slotCode] ?: return VendingResult.InvalidProduct(slotCode)
        val product = slot.product ?: return VendingResult.InvalidProduct(slotCode)
        
        transition(VendingMachineState.DISPENSING)
        
        when (val result = dispenser.dispense(slot)) {
            is DispenseResult.Success -> {
                notifyProductDispensed(result.product)
                
                val changeAmount = currentBalance - product.price
                val change = calculateChange(changeAmount)
                
                if (change == null && changeAmount > 0) {
                    // Cannot provide change - compensate somehow
                    notifyError("Cannot provide exact change: $changeAmount")
                    reset()
                    return VendingResult.NoChangeAvailable(changeAmount)
                }
                
                if (slot.isEmpty()) {
                    notifySlotEmpty(slotCode)
                }
                
                change?.let { notifyChangeReturned(it) }
                reset()
                
                return VendingResult.Success(result.product, change ?: emptyList())
            }
            is DispenseResult.Stuck -> {
                notifyError("Product stuck in slot $slotCode")
                // Return full amount as compensation
                val refund = calculateChange(currentBalance)
                reset()
                return VendingResult.ProductStuck(slotCode)
            }
            is DispenseResult.Empty -> {
                reset()
                return VendingResult.OutOfStock(slotCode)
            }
        }
    }
    
    private fun calculateChange(amount: Double): List<CoinType>? {
        if (amount <= 0) return emptyList()
        
        val change = mutableListOf<CoinType>()
        var remaining = (amount * 100).toInt()
        
        val coinsByValue = CoinType.entries.sortedByDescending { it.value }
        
        for (coin in coinsByValue) {
            val coinValue = (coin.value * 100).toInt()
            while (remaining >= coinValue && (cashInventory.coins[coin] ?: 0) > 0) {
                if (cashInventory.removeCoin(coin)) {
                    change.add(coin)
                    remaining -= coinValue
                }
            }
        }
        
        return if (remaining == 0) change else null
    }
    
    fun cancel(): VendingResult {
        if (state == VendingMachineState.IDLE) {
            return VendingResult.Cancelled(emptyList())
        }
        
        val refund = calculateChange(currentBalance)
        refund?.let { notifyChangeReturned(it) }
        reset()
        
        return VendingResult.Cancelled(refund ?: emptyList())
    }
    
    private fun reset() {
        currentBalance = 0.0
        selectedSlot = null
        insertedPayments.clear()
        state = VendingMachineState.IDLE
    }
    
    fun enterAdminMode(): Boolean {
        if (state != VendingMachineState.IDLE && state != VendingMachineState.OUT_OF_SERVICE) {
            return false
        }
        transition(VendingMachineState.ADMIN_MODE)
        return true
    }
    
    fun exitAdminMode(): Boolean {
        if (state != VendingMachineState.ADMIN_MODE) return false
        transition(VendingMachineState.IDLE)
        return true
    }
    
    fun refillSlot(slotCode: String, quantity: Int): Boolean {
        if (state != VendingMachineState.ADMIN_MODE) return false
        
        val slot = slots[slotCode] ?: return false
        val toAdd = minOf(quantity, slot.maxCapacity - slot.quantity)
        slots[slotCode] = slot.copy(quantity = slot.quantity + toAdd)
        return true
    }
    
    fun addSlot(slot: Slot): Boolean {
        if (state != VendingMachineState.ADMIN_MODE) return false
        if (slots.containsKey(slot.code)) return false
        slots[slot.code] = slot
        return true
    }
    
    fun refillCash(coins: Map<CoinType, Int>): Boolean {
        if (state != VendingMachineState.ADMIN_MODE) return false
        coins.forEach { (coin, count) ->
            cashInventory.addCoin(coin, count)
        }
        return true
    }
    
    fun getInventoryStatus(): InventoryStatus {
        val lowStock = slots.filter { it.value.quantity in 1..2 }.keys.toList()
        val empty = slots.filter { it.value.isEmpty() }.keys.toList()
        val expired = slots.filter { it.value.product?.isExpired() == true }.keys.toList()
        
        return InventoryStatus(
            slots = slots.toMap(),
            lowStockSlots = lowStock,
            emptySlots = empty,
            expiredProducts = expired
        )
    }
    
    fun setOutOfService() {
        state = VendingMachineState.OUT_OF_SERVICE
    }
    
    fun addObserver(observer: VendingMachineObserver) {
        observers.add(observer)
    }
    
    fun removeObserver(observer: VendingMachineObserver) {
        observers.remove(observer)
    }
    
    private fun notifyProductDispensed(product: Product) {
        observers.forEach { it.onProductDispensed(product) }
    }
    
    private fun notifyPaymentReceived(amount: Double) {
        observers.forEach { it.onPaymentReceived(amount) }
    }
    
    private fun notifyChangeReturned(change: List<CoinType>) {
        observers.forEach { it.onChangeReturned(change) }
    }
    
    private fun notifySlotEmpty(slotCode: String) {
        observers.forEach { it.onSlotEmpty(slotCode) }
    }
    
    private fun notifyError(message: String) {
        observers.forEach { it.onError(message) }
    }
}

/** Default dispenser implementation */
class DefaultDispenser : Dispenser {
    override fun dispense(slot: Slot): DispenseResult {
        val product = slot.dispense()
        return if (product != null) {
            DispenseResult.Success(product)
        } else {
            DispenseResult.Empty
        }
    }
}
