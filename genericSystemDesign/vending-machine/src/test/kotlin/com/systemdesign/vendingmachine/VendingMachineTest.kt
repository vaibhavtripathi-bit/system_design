package com.systemdesign.vendingmachine

import com.systemdesign.vendingmachine.common.*
import com.systemdesign.vendingmachine.approach_01_state_machine.*
import com.systemdesign.vendingmachine.approach_02_strategy_payment.*
import com.systemdesign.vendingmachine.approach_03_chain_of_responsibility.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import java.time.LocalDate

class VendingMachineTest {
    
    private fun createTestSlots(): Map<String, Slot> {
        return mapOf(
            "A1" to Slot("A1", Product("COLA", "Cola", 1.50), quantity = 5),
            "A2" to Slot("A2", Product("WATER", "Water", 1.00), quantity = 3),
            "A3" to Slot("A3", Product("CHIPS", "Chips", 2.00), quantity = 0), // Empty
            "A4" to Slot("A4", Product("EXPIRED", "Expired Item", 1.00, 
                LocalDate.now().minusDays(1)), quantity = 2) // Expired
        )
    }
    
    private fun createCashInventory(): CashInventory {
        val inventory = CashInventory()
        inventory.addCoin(CoinType.QUARTER, 20)
        inventory.addCoin(CoinType.DIME, 20)
        inventory.addCoin(CoinType.NICKEL, 20)
        inventory.addCoin(CoinType.DOLLAR, 10)
        return inventory
    }
    
    @Nested
    inner class StateMachineVendingMachineTest {
        
        private lateinit var machine: StateMachineVendingMachine
        
        @BeforeEach
        fun setup() {
            machine = StateMachineVendingMachine(
                initialInventory = createTestSlots(),
                initialCash = createCashInventory()
            )
        }
        
        @Test
        fun `starts in idle state`() {
            assertEquals(VendingMachineState.IDLE, machine.getState())
            assertEquals(0.0, machine.getBalance())
        }
        
        @Test
        fun `accepts coin payment and updates balance`() {
            val result = machine.insertPayment(Payment.CoinPayment(CoinType.QUARTER))
            
            assertTrue(result)
            assertEquals(VendingMachineState.HAS_MONEY, machine.getState())
            assertEquals(0.25, machine.getBalance(), 0.001)
        }
        
        @Test
        fun `accepts note payment`() {
            val result = machine.insertPayment(Payment.NotePayment(NoteType.ONE))
            
            assertTrue(result)
            assertEquals(1.0, machine.getBalance(), 0.001)
        }
        
        @Test
        fun `dispenses product with exact change`() {
            machine.insertPayment(Payment.NotePayment(NoteType.ONE))
            machine.insertPayment(Payment.CoinPayment(CoinType.HALF_DOLLAR))
            
            val result = machine.selectProduct("A1")
            
            assertTrue(result is VendingResult.Success)
            val success = result as VendingResult.Success
            assertEquals("Cola", success.product.name)
            assertTrue(success.change.isEmpty())
        }
        
        @Test
        fun `dispenses product with change`() {
            machine.insertPayment(Payment.NotePayment(NoteType.FIVE))
            
            val result = machine.selectProduct("A2") // Water = $1.00
            
            assertTrue(result is VendingResult.Success)
            val success = result as VendingResult.Success
            assertEquals("Water", success.product.name)
            
            val changeTotal = success.change.sumOf { it.value }
            assertEquals(4.0, changeTotal, 0.01)
        }
        
        @Test
        fun `rejects insufficient funds`() {
            machine.insertPayment(Payment.CoinPayment(CoinType.QUARTER))
            
            val result = machine.selectProduct("A1") // Cola = $1.50
            
            assertTrue(result is VendingResult.InsufficientFunds)
            val insufficient = result as VendingResult.InsufficientFunds
            assertEquals(1.50, insufficient.required, 0.01)
            assertEquals(0.25, insufficient.inserted, 0.01)
        }
        
        @Test
        fun `rejects out of stock product`() {
            machine.insertPayment(Payment.NotePayment(NoteType.FIVE))
            
            val result = machine.selectProduct("A3")
            
            assertTrue(result is VendingResult.OutOfStock)
        }
        
        @Test
        fun `rejects expired product`() {
            machine.insertPayment(Payment.NotePayment(NoteType.FIVE))
            
            val result = machine.selectProduct("A4")
            
            assertTrue(result is VendingResult.ProductExpired)
        }
        
        @Test
        fun `rejects invalid product code`() {
            machine.insertPayment(Payment.NotePayment(NoteType.ONE))
            
            val result = machine.selectProduct("Z9")
            
            assertTrue(result is VendingResult.InvalidProduct)
        }
        
        @Test
        fun `cancel returns inserted money`() {
            machine.insertPayment(Payment.NotePayment(NoteType.ONE))
            machine.insertPayment(Payment.CoinPayment(CoinType.QUARTER))
            
            val result = machine.cancel()
            
            assertTrue(result is VendingResult.Cancelled)
            assertEquals(VendingMachineState.IDLE, machine.getState())
            assertEquals(0.0, machine.getBalance())
        }
        
        @Test
        fun `admin mode allows refill`() {
            machine.enterAdminMode()
            assertTrue(machine.refillSlot("A3", 5))
            
            val status = machine.getInventoryStatus()
            assertEquals(5, status.slots["A3"]?.quantity)
            
            machine.exitAdminMode()
        }
        
        @Test
        fun `cannot refill outside admin mode`() {
            assertFalse(machine.refillSlot("A3", 5))
        }
        
        @Test
        fun `cannot insert payment in admin mode`() {
            machine.enterAdminMode()
            
            val result = machine.insertPayment(Payment.CoinPayment(CoinType.QUARTER))
            
            assertFalse(result)
        }
        
        @Test
        fun `observer notified on events`() {
            var dispensedProduct: Product? = null
            var paymentAmount = 0.0
            
            machine.addObserver(object : VendingMachineObserver {
                override fun onProductDispensed(product: Product) { dispensedProduct = product }
                override fun onPaymentReceived(amount: Double) { paymentAmount += amount }
                override fun onChangeReturned(change: List<CoinType>) {}
                override fun onSlotEmpty(slotCode: String) {}
                override fun onError(message: String) {}
            })
            
            machine.insertPayment(Payment.NotePayment(NoteType.FIVE))
            machine.selectProduct("A2")
            
            assertEquals(5.0, paymentAmount, 0.01)
            assertEquals("Water", dispensedProduct?.name)
        }
        
        @Test
        fun `inventory status shows empty and low stock slots`() {
            val status = machine.getInventoryStatus()
            
            assertTrue("A3" in status.emptySlots)
            assertTrue("A4" in status.expiredProducts)
        }
    }
    
    @Nested
    inner class StrategyPaymentTest {
        
        @Test
        fun `cash strategy processes coin payment`() {
            val inventory = CashInventory()
            val strategy = CashPaymentStrategy(inventory)
            
            val payment = Payment.CoinPayment(CoinType.QUARTER)
            val result = strategy.process(payment)
            
            assertTrue(result is PaymentProcessResult.Success)
            assertEquals(1, inventory.coins[CoinType.QUARTER])
        }
        
        @Test
        fun `cash strategy validates cash payments only`() {
            val strategy = CashPaymentStrategy(CashInventory())
            
            val coinResult = strategy.validate(Payment.CoinPayment(CoinType.QUARTER))
            val cardResult = strategy.validate(Payment.CardPayment("1234", 1.0))
            
            assertTrue(coinResult is PaymentValidationResult.Valid)
            assertTrue(cardResult is PaymentValidationResult.Invalid)
        }
        
        @Test
        fun `card strategy processes card payment`() {
            val strategy = CardPaymentStrategy()
            val payment = Payment.CardPayment("1234567890123", 10.0)
            
            val result = strategy.process(payment)
            
            assertTrue(result is PaymentProcessResult.Success)
        }
        
        @Test
        fun `card strategy rejects invalid card number`() {
            val strategy = CardPaymentStrategy()
            val payment = Payment.CardPayment("123", 10.0) // Too short
            
            val result = strategy.validate(payment)
            
            assertTrue(result is PaymentValidationResult.Invalid)
        }
        
        @Test
        fun `card strategy handles processor failure`() {
            val processor = MockCardProcessor()
            processor.shouldFail = true
            val strategy = CardPaymentStrategy(processor)
            
            val payment = Payment.CardPayment("1234567890123", 10.0)
            val result = strategy.process(payment)
            
            assertTrue(result is PaymentProcessResult.Failure)
        }
        
        @Test
        fun `strategy based processor routes to correct strategy`() {
            val processor = StrategyBasedPaymentProcessor()
            processor.registerStrategy("cash", CashPaymentStrategy(CashInventory()))
            processor.registerStrategy("card", CardPaymentStrategy())
            
            val coinResult = processor.processPayment(Payment.CoinPayment(CoinType.QUARTER))
            val cardResult = processor.processPayment(Payment.CardPayment("1234567890123", 10.0))
            
            assertTrue(coinResult is PaymentProcessResult.Success)
            assertTrue(cardResult is PaymentProcessResult.Success)
        }
        
        @Test
        fun `mobile payment strategy works`() {
            val strategy = MobilePaymentStrategy()
            val payment = Payment.MobilePayment("TXN-123", 5.0)
            
            val result = strategy.process(payment)
            
            assertTrue(result is PaymentProcessResult.Success)
        }
    }
    
    @Nested
    inner class ChainOfResponsibilityTest {
        
        @Test
        fun `payment chain validates amount`() {
            val chain = ValidationChainBuilder.paymentChain()
            
            val validContext = ValidationContext(
                payment = Payment.CoinPayment(CoinType.QUARTER),
                product = null,
                slot = null,
                currentBalance = 0.0,
                cashInventory = null
            )
            
            val result = chain.validate(validContext)
            
            assertTrue(result is ValidationResult.Valid)
        }
        
        @Test
        fun `purchase chain validates stock`() {
            val chain = ValidationChainBuilder.purchaseChain()
            
            val emptySlot = Slot("A1", Product("COLA", "Cola", 1.50), quantity = 0)
            val context = ValidationContext(
                payment = null,
                product = emptySlot.product,
                slot = emptySlot,
                currentBalance = 5.0,
                cashInventory = createCashInventory()
            )
            
            val result = chain.validate(context)
            
            assertTrue(result is ValidationResult.Invalid)
            assertEquals("OUT_OF_STOCK", (result as ValidationResult.Invalid).code)
        }
        
        @Test
        fun `purchase chain validates expiration`() {
            val expiredProduct = Product("OLD", "Old Item", 1.0, LocalDate.now().minusDays(1))
            val chain = ValidationChainBuilder.purchaseChain()
            
            val context = ValidationContext(
                payment = null,
                product = expiredProduct,
                slot = Slot("A1", expiredProduct, quantity = 5),
                currentBalance = 5.0,
                cashInventory = createCashInventory()
            )
            
            val result = chain.validate(context)
            
            assertTrue(result is ValidationResult.Invalid)
            assertEquals("EXPIRED", (result as ValidationResult.Invalid).code)
        }
        
        @Test
        fun `purchase chain validates balance`() {
            val chain = ValidationChainBuilder.purchaseChain()
            val slot = Slot("A1", Product("COLA", "Cola", 5.0), quantity = 3)
            
            val context = ValidationContext(
                payment = null,
                product = slot.product,
                slot = slot,
                currentBalance = 2.0,
                cashInventory = createCashInventory()
            )
            
            val result = chain.validate(context)
            
            assertTrue(result is ValidationResult.Invalid)
            assertEquals("INSUFFICIENT_BALANCE", (result as ValidationResult.Invalid).code)
        }
        
        @Test
        fun `custom chain can be built`() {
            val chain = ValidationChainBuilder()
                .add(StockValidator())
                .add(BalanceValidator())
                .build()
            
            val slot = Slot("A1", Product("COLA", "Cola", 1.0), quantity = 5)
            val context = ValidationContext(
                payment = null,
                product = slot.product,
                slot = slot,
                currentBalance = 5.0,
                cashInventory = null
            )
            
            val result = chain.validate(context)
            
            assertTrue(result is ValidationResult.Valid)
        }
        
        @Test
        fun `chain validated machine inserts payment`() {
            val machine = ChainValidatedVendingMachine()
            machine.addSlot(Slot("A1", Product("COLA", "Cola", 1.50), quantity = 5))
            machine.refillCash(mapOf(CoinType.QUARTER to 20))
            
            val result = machine.insertPayment(Payment.CoinPayment(CoinType.QUARTER))
            
            assertTrue(result is ValidationResult.Valid)
            assertEquals(0.25, machine.getBalance(), 0.001)
        }
        
        @Test
        fun `chain validated machine validates product selection`() {
            val machine = ChainValidatedVendingMachine()
            machine.addSlot(Slot("A1", Product("COLA", "Cola", 1.50), quantity = 5))
            machine.refillCash(mapOf(CoinType.QUARTER to 20, CoinType.DOLLAR to 10))
            
            machine.insertPayment(Payment.NotePayment(NoteType.FIVE))
            
            val result = machine.selectProduct("A1")
            
            assertTrue(result is ValidationResult.Valid)
        }
    }
}
