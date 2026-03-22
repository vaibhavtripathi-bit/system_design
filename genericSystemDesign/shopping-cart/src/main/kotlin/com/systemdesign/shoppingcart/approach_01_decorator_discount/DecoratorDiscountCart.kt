package com.systemdesign.shoppingcart.approach_01_decorator_discount

import com.systemdesign.shoppingcart.common.Cart

interface PriceCalculator {
    fun calculateTotal(cart: Cart): Double
}

class BasePriceCalculator : PriceCalculator {
    override fun calculateTotal(cart: Cart) = cart.getSubtotal()
}

abstract class DiscountDecorator(protected val wrapped: PriceCalculator) : PriceCalculator

class FlatDiscountDecorator(wrapped: PriceCalculator, private val amount: Double) : DiscountDecorator(wrapped) {
    override fun calculateTotal(cart: Cart) = (wrapped.calculateTotal(cart) - amount).coerceAtLeast(0.0)
}

class PercentageDiscountDecorator(wrapped: PriceCalculator, private val percent: Double) : DiscountDecorator(wrapped) {
    override fun calculateTotal(cart: Cart) = wrapped.calculateTotal(cart) * (1 - percent / 100)
}

class BOGODecorator(wrapped: PriceCalculator, private val category: String) : DiscountDecorator(wrapped) {
    override fun calculateTotal(cart: Cart): Double {
        val base = wrapped.calculateTotal(cart)
        val bogoItems = cart.items.filter { it.product.category == category }
        val discount = bogoItems.sumOf { (it.quantity / 2) * it.product.price }
        return base - discount
    }
}

class MinPurchaseDecorator(
    wrapped: PriceCalculator,
    private val minAmount: Double,
    private val discountPercent: Double
) : DiscountDecorator(wrapped) {
    override fun calculateTotal(cart: Cart): Double {
        val base = wrapped.calculateTotal(cart)
        return if (base >= minAmount) base * (1 - discountPercent / 100) else base
    }
}

class DiscountStackBuilder {
    private var calculator: PriceCalculator = BasePriceCalculator()
    
    fun withFlatDiscount(amount: Double) = apply { calculator = FlatDiscountDecorator(calculator, amount) }
    fun withPercentage(percent: Double) = apply { calculator = PercentageDiscountDecorator(calculator, percent) }
    fun withBOGO(category: String) = apply { calculator = BOGODecorator(calculator, category) }
    fun withMinPurchase(min: Double, percent: Double) = apply { calculator = MinPurchaseDecorator(calculator, min, percent) }
    fun build(): PriceCalculator = calculator
}
