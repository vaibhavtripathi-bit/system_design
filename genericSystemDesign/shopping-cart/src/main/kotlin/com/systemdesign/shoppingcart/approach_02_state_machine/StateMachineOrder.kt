package com.systemdesign.shoppingcart.approach_02_state_machine

import com.systemdesign.shoppingcart.common.*

class OrderStateMachine(private val order: Order) {
    private val validTransitions = mapOf(
        OrderState.CREATED to setOf(OrderState.PAYMENT_PENDING, OrderState.CANCELLED),
        OrderState.PAYMENT_PENDING to setOf(OrderState.PAID, OrderState.CANCELLED),
        OrderState.PAID to setOf(OrderState.PROCESSING, OrderState.REFUNDED),
        OrderState.PROCESSING to setOf(OrderState.SHIPPED, OrderState.REFUNDED),
        OrderState.SHIPPED to setOf(OrderState.DELIVERED),
        OrderState.DELIVERED to setOf(OrderState.REFUNDED),
        OrderState.CANCELLED to emptySet(),
        OrderState.REFUNDED to emptySet()
    )
    
    fun getState() = order.state
    
    fun canTransition(to: OrderState) = validTransitions[order.state]?.contains(to) == true
    
    fun transition(to: OrderState): Boolean {
        if (!canTransition(to)) return false
        order.state = to
        return true
    }
    
    fun processPayment(payment: Payment): Boolean {
        if (order.state != OrderState.PAYMENT_PENDING) return false
        order.payment = payment
        return if (payment.status == PaymentStatus.SUCCESS) {
            transition(OrderState.PAID)
        } else false
    }
    
    fun cancel(): Boolean = transition(OrderState.CANCELLED)
    
    fun refund(): Boolean {
        if (!canTransition(OrderState.REFUNDED)) return false
        order.payment?.status = PaymentStatus.PENDING
        return transition(OrderState.REFUNDED)
    }
}

class OrderService {
    fun createOrder(cart: Cart): Order {
        if (cart.isEmpty()) throw IllegalArgumentException("Cart is empty")
        return Order(cart = cart, state = OrderState.CREATED)
    }
    
    fun checkout(order: Order): OrderStateMachine {
        val machine = OrderStateMachine(order)
        machine.transition(OrderState.PAYMENT_PENDING)
        return machine
    }
}
