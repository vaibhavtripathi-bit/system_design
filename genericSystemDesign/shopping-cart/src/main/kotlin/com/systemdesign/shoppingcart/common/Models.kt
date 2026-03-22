package com.systemdesign.shoppingcart.common

import java.time.LocalDateTime
import java.util.UUID

data class Product(val id: String, val name: String, val price: Double, val category: String)
data class CartItem(val product: Product, var quantity: Int)
data class Cart(val id: String = UUID.randomUUID().toString(), val items: MutableList<CartItem> = mutableListOf()) {
    fun addItem(product: Product, quantity: Int = 1) {
        val existing = items.find { it.product.id == product.id }
        if (existing != null) existing.quantity += quantity
        else items.add(CartItem(product, quantity))
    }
    fun removeItem(productId: String) = items.removeIf { it.product.id == productId }
    fun getSubtotal() = items.sumOf { it.product.price * it.quantity }
    fun isEmpty() = items.isEmpty()
}

enum class OrderState { CREATED, PAYMENT_PENDING, PAID, PROCESSING, SHIPPED, DELIVERED, CANCELLED, REFUNDED }
enum class PaymentStatus { PENDING, SUCCESS, FAILED }
data class Payment(val id: String = UUID.randomUUID().toString(), val amount: Double, val method: String, var status: PaymentStatus = PaymentStatus.PENDING)
data class Order(
    val id: String = UUID.randomUUID().toString(),
    val cart: Cart,
    var state: OrderState = OrderState.CREATED,
    var payment: Payment? = null,
    val createdAt: LocalDateTime = LocalDateTime.now()
)

interface InventoryService {
    fun isAvailable(productId: String, quantity: Int): Boolean
    fun reserve(productId: String, quantity: Int): Boolean
    fun release(productId: String, quantity: Int)
}

interface CartObserver {
    fun onItemAdded(item: CartItem)
    fun onItemRemoved(productId: String)
    fun onCartCleared()
}
