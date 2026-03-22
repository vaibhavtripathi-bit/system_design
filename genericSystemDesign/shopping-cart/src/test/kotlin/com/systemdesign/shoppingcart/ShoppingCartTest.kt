package com.systemdesign.shoppingcart

import com.systemdesign.shoppingcart.common.*
import com.systemdesign.shoppingcart.approach_01_decorator_discount.*
import com.systemdesign.shoppingcart.approach_02_state_machine.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested

class ShoppingCartTest {
    private val product1 = Product("P1", "Laptop", 1000.0, "electronics")
    private val product2 = Product("P2", "Mouse", 50.0, "electronics")
    private val product3 = Product("P3", "Shirt", 30.0, "clothing")
    
    @Nested
    inner class CartTest {
        private lateinit var cart: Cart
        
        @BeforeEach
        fun setup() { cart = Cart() }
        
        @Test
        fun `add item to cart`() {
            cart.addItem(product1)
            assertEquals(1, cart.items.size)
        }
        
        @Test
        fun `add same item increases quantity`() {
            cart.addItem(product1, 2)
            cart.addItem(product1, 3)
            assertEquals(5, cart.items.first().quantity)
        }
        
        @Test
        fun `remove item from cart`() {
            cart.addItem(product1)
            cart.removeItem("P1")
            assertTrue(cart.isEmpty())
        }
        
        @Test
        fun `subtotal calculation`() {
            cart.addItem(product1, 2)
            cart.addItem(product2, 1)
            assertEquals(2050.0, cart.getSubtotal())
        }
    }
    
    @Nested
    inner class DiscountDecoratorTest {
        private lateinit var cart: Cart
        
        @BeforeEach
        fun setup() {
            cart = Cart()
            cart.addItem(product1)
            cart.addItem(product2)
        }
        
        @Test
        fun `flat discount`() {
            val calc = FlatDiscountDecorator(BasePriceCalculator(), 100.0)
            assertEquals(950.0, calc.calculateTotal(cart))
        }
        
        @Test
        fun `percentage discount`() {
            val calc = PercentageDiscountDecorator(BasePriceCalculator(), 10.0)
            assertEquals(945.0, calc.calculateTotal(cart))
        }
        
        @Test
        fun `BOGO discount`() {
            cart.addItem(product3, 3) // 3 shirts at $30 each
            val calc = BOGODecorator(BasePriceCalculator(), "clothing")
            // Base: 1000 + 50 + 90 = 1140
            // BOGO: (3/2) * 30 = 30 discount
            // Total: 1140 - 30 = 1110
            assertEquals(1110.0, calc.calculateTotal(cart))
        }
        
        @Test
        fun `stacked discounts`() {
            val calc = DiscountStackBuilder()
                .withPercentage(10.0)
                .withFlatDiscount(50.0)
                .build()
            assertEquals(895.0, calc.calculateTotal(cart))
        }
        
        @Test
        fun `min purchase discount applies`() {
            val calc = MinPurchaseDecorator(BasePriceCalculator(), 500.0, 5.0)
            assertEquals(997.5, calc.calculateTotal(cart))
        }
    }
    
    @Nested
    inner class OrderStateMachineTest {
        private lateinit var order: Order
        private lateinit var machine: OrderStateMachine
        
        @BeforeEach
        fun setup() {
            val cart = Cart()
            cart.addItem(product1)
            order = Order(cart = cart)
            machine = OrderStateMachine(order)
        }
        
        @Test
        fun `order starts in CREATED`() = assertEquals(OrderState.CREATED, machine.getState())
        
        @Test
        fun `can transition to PAYMENT_PENDING`() {
            assertTrue(machine.transition(OrderState.PAYMENT_PENDING))
            assertEquals(OrderState.PAYMENT_PENDING, machine.getState())
        }
        
        @Test
        fun `invalid transition fails`() {
            assertFalse(machine.transition(OrderState.SHIPPED))
        }
        
        @Test
        fun `payment processing`() {
            machine.transition(OrderState.PAYMENT_PENDING)
            val payment = Payment(amount = 1000.0, method = "CARD", status = PaymentStatus.SUCCESS)
            assertTrue(machine.processPayment(payment))
            assertEquals(OrderState.PAID, machine.getState())
        }
        
        @Test
        fun `cancellation from CREATED`() {
            assertTrue(machine.cancel())
            assertEquals(OrderState.CANCELLED, machine.getState())
        }
        
        @Test
        fun `refund from PAID`() {
            machine.transition(OrderState.PAYMENT_PENDING)
            machine.processPayment(Payment(amount = 1000.0, method = "CARD", status = PaymentStatus.SUCCESS))
            assertTrue(machine.refund())
            assertEquals(OrderState.REFUNDED, machine.getState())
        }
        
        @Test
        fun `full order lifecycle`() {
            machine.transition(OrderState.PAYMENT_PENDING)
            machine.processPayment(Payment(amount = 1000.0, method = "CARD", status = PaymentStatus.SUCCESS))
            machine.transition(OrderState.PROCESSING)
            machine.transition(OrderState.SHIPPED)
            machine.transition(OrderState.DELIVERED)
            assertEquals(OrderState.DELIVERED, machine.getState())
        }
    }
}
