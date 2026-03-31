package com.systemdesign.shoppingcart.approach_03_strategy_pricing

import com.systemdesign.shoppingcart.common.*
import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * Approach 3: Strategy Pattern for Pricing Rules
 *
 * PricingStrategy interface with implementations for different customer
 * types, quantity thresholds, and time-based rules. A PricingEngine
 * selects and composes strategies based on context, then applies tax
 * calculation strategies on top.
 *
 * Pattern: Strategy
 *
 * Trade-offs:
 * + Each pricing rule is isolated and independently testable
 * + New customer tiers or seasonal rules require only a new strategy class
 * + Tax strategies are composable and region-aware
 * - Strategy selection logic can become complex with many overlapping rules
 * - Interaction between multiple active strategies needs explicit priority/ordering
 *
 * When to use:
 * - When pricing varies by customer type (member, wholesale, regular)
 * - When quantity-based or time-based pricing rules change frequently
 * - When tax calculation differs by region or product category
 *
 * Extensibility:
 * - New pricing tier: Implement PricingStrategy
 * - New tax region: Implement TaxStrategy
 * - New selection rule: Add a PricingRule to PricingEngine
 */

enum class CustomerType { REGULAR, MEMBER, WHOLESALE, EMPLOYEE }

data class CustomerProfile(
    val id: String,
    val type: CustomerType,
    val memberSince: LocalDateTime? = null,
    val loyaltyPoints: Int = 0
)

data class PricingLineItem(
    val product: Product,
    val quantity: Int,
    val unitPrice: Double,
    val lineTotal: Double,
    val discount: Double,
    val discountReason: String?
)

data class TaxLineItem(
    val description: String,
    val rate: Double,
    val taxableAmount: Double,
    val taxAmount: Double
)

data class PricingResult(
    val lineItems: List<PricingLineItem>,
    val subtotal: Double,
    val totalDiscount: Double,
    val taxItems: List<TaxLineItem>,
    val totalTax: Double,
    val grandTotal: Double
) {
    fun format(): String {
        val lines = mutableListOf("===== ORDER SUMMARY =====")
        for (item in lineItems) {
            lines.add("${item.product.name} x${item.quantity}  $${"%.2f".format(item.lineTotal)}")
            if (item.discount > 0 && item.discountReason != null) {
                lines.add("  Discount: -$${"%.2f".format(item.discount)} (${item.discountReason})")
            }
        }
        lines.add("-------------------------")
        lines.add("Subtotal:  $${"%.2f".format(subtotal)}")
        if (totalDiscount > 0) lines.add("Discounts: -$${"%.2f".format(totalDiscount)}")
        for (tax in taxItems) {
            lines.add("${tax.description}: $${"%.2f".format(tax.taxAmount)}")
        }
        lines.add("=========================")
        lines.add("TOTAL:     $${"%.2f".format(grandTotal)}")
        return lines.joinToString("\n")
    }
}

interface PricingStrategy {
    fun calculatePrice(item: CartItem, customer: CustomerProfile): PricingLineItem
}

class RegularPricing : PricingStrategy {
    override fun calculatePrice(item: CartItem, customer: CustomerProfile): PricingLineItem {
        val lineTotal = item.product.price * item.quantity
        return PricingLineItem(
            product = item.product,
            quantity = item.quantity,
            unitPrice = item.product.price,
            lineTotal = lineTotal,
            discount = 0.0,
            discountReason = null
        )
    }
}

class MemberPricing(
    private val baseDiscountPercent: Double = 10.0,
    private val loyaltyBonusThreshold: Int = 1000,
    private val loyaltyBonusPercent: Double = 5.0
) : PricingStrategy {
    override fun calculatePrice(item: CartItem, customer: CustomerProfile): PricingLineItem {
        val baseTotal = item.product.price * item.quantity
        var discountPercent = baseDiscountPercent
        if (customer.loyaltyPoints >= loyaltyBonusThreshold) {
            discountPercent += loyaltyBonusPercent
        }
        val discount = baseTotal * discountPercent / 100.0
        val reason = if (customer.loyaltyPoints >= loyaltyBonusThreshold)
            "Member ${baseDiscountPercent.toInt()}% + Loyalty ${loyaltyBonusPercent.toInt()}%"
        else
            "Member ${baseDiscountPercent.toInt()}%"

        return PricingLineItem(
            product = item.product,
            quantity = item.quantity,
            unitPrice = item.product.price,
            lineTotal = baseTotal - discount,
            discount = discount,
            discountReason = reason
        )
    }
}

class WholesalePricing(
    private val tiers: List<QuantityTier> = listOf(
        QuantityTier(10, 5.0),
        QuantityTier(50, 10.0),
        QuantityTier(100, 15.0),
        QuantityTier(500, 20.0)
    )
) : PricingStrategy {

    data class QuantityTier(val minQuantity: Int, val discountPercent: Double)

    override fun calculatePrice(item: CartItem, customer: CustomerProfile): PricingLineItem {
        val baseTotal = item.product.price * item.quantity
        val applicableTier = tiers
            .filter { item.quantity >= it.minQuantity }
            .maxByOrNull { it.minQuantity }

        val discountPercent = applicableTier?.discountPercent ?: 0.0
        val discount = baseTotal * discountPercent / 100.0
        val reason = applicableTier?.let { "Wholesale ${it.discountPercent.toInt()}% (${it.minQuantity}+ units)" }

        return PricingLineItem(
            product = item.product,
            quantity = item.quantity,
            unitPrice = item.product.price,
            lineTotal = baseTotal - discount,
            discount = discount,
            discountReason = reason
        )
    }
}

class SeasonalPricing(
    private val fallback: PricingStrategy = RegularPricing(),
    private val rules: List<SeasonalRule> = listOf(
        SeasonalRule("Weekend Sale", setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), 15.0),
        SeasonalRule("Friday Flash", setOf(DayOfWeek.FRIDAY), 8.0)
    )
) : PricingStrategy {

    data class SeasonalRule(
        val name: String,
        val activeDays: Set<DayOfWeek>,
        val discountPercent: Double,
        val applicableCategories: Set<String>? = null
    )

    override fun calculatePrice(item: CartItem, customer: CustomerProfile): PricingLineItem {
        val now = LocalDateTime.now()
        val activeRule = rules
            .filter { now.dayOfWeek in it.activeDays }
            .filter { it.applicableCategories == null || item.product.category in it.applicableCategories }
            .maxByOrNull { it.discountPercent }

        if (activeRule == null) return fallback.calculatePrice(item, customer)

        val baseTotal = item.product.price * item.quantity
        val discount = baseTotal * activeRule.discountPercent / 100.0

        return PricingLineItem(
            product = item.product,
            quantity = item.quantity,
            unitPrice = item.product.price,
            lineTotal = baseTotal - discount,
            discount = discount,
            discountReason = activeRule.name
        )
    }
}

interface TaxStrategy {
    fun calculateTax(lineItems: List<PricingLineItem>): List<TaxLineItem>
}

class FlatTaxStrategy(
    private val taxRate: Double = 0.08,
    private val description: String = "Sales Tax"
) : TaxStrategy {
    override fun calculateTax(lineItems: List<PricingLineItem>): List<TaxLineItem> {
        val taxable = lineItems.sumOf { it.lineTotal }
        return listOf(TaxLineItem(description, taxRate, taxable, taxable * taxRate))
    }
}

class CategoryTaxStrategy(
    private val categoryRates: Map<String, Double>,
    private val defaultRate: Double = 0.08
) : TaxStrategy {
    override fun calculateTax(lineItems: List<PricingLineItem>): List<TaxLineItem> {
        return lineItems
            .groupBy { it.product.category }
            .map { (category, items) ->
                val rate = categoryRates[category] ?: defaultRate
                val taxable = items.sumOf { it.lineTotal }
                TaxLineItem("Tax ($category)", rate, taxable, taxable * rate)
            }
    }
}

class ExemptionTaxStrategy(
    private val exemptCategories: Set<String>,
    private val taxRate: Double = 0.08
) : TaxStrategy {
    override fun calculateTax(lineItems: List<PricingLineItem>): List<TaxLineItem> {
        val taxable = lineItems.filter { it.product.category !in exemptCategories }
        val exempt = lineItems.filter { it.product.category in exemptCategories }
        val items = mutableListOf<TaxLineItem>()

        val taxableTotal = taxable.sumOf { it.lineTotal }
        if (taxableTotal > 0) {
            items.add(TaxLineItem("Sales Tax", taxRate, taxableTotal, taxableTotal * taxRate))
        }
        val exemptTotal = exempt.sumOf { it.lineTotal }
        if (exemptTotal > 0) {
            items.add(TaxLineItem("Tax Exempt", 0.0, exemptTotal, 0.0))
        }
        return items
    }
}

data class PricingRule(
    val name: String,
    val customerTypes: Set<CustomerType>,
    val strategy: PricingStrategy,
    val priority: Int = 0
)

class PricingEngine(
    private val rules: List<PricingRule>,
    private val defaultStrategy: PricingStrategy = RegularPricing(),
    private val taxStrategy: TaxStrategy = FlatTaxStrategy()
) {
    fun calculateTotal(cart: Cart, customer: CustomerProfile): PricingResult {
        val strategy = selectStrategy(customer)
        val lineItems = cart.items.map { strategy.calculatePrice(it, customer) }
        val subtotal = lineItems.sumOf { it.lineTotal }
        val totalDiscount = lineItems.sumOf { it.discount }
        val taxItems = taxStrategy.calculateTax(lineItems)
        val totalTax = taxItems.sumOf { it.taxAmount }

        return PricingResult(
            lineItems = lineItems,
            subtotal = subtotal,
            totalDiscount = totalDiscount,
            taxItems = taxItems,
            totalTax = totalTax,
            grandTotal = subtotal + totalTax
        )
    }

    private fun selectStrategy(customer: CustomerProfile): PricingStrategy {
        return rules
            .filter { customer.type in it.customerTypes }
            .maxByOrNull { it.priority }
            ?.strategy
            ?: defaultStrategy
    }
}

class PricingEngineBuilder {
    private val rules = mutableListOf<PricingRule>()
    private var defaultStrategy: PricingStrategy = RegularPricing()
    private var taxStrategy: TaxStrategy = FlatTaxStrategy()

    fun withRule(name: String, types: Set<CustomerType>, strategy: PricingStrategy, priority: Int = 0) = apply {
        rules.add(PricingRule(name, types, strategy, priority))
    }

    fun withMemberPricing(discountPercent: Double = 10.0) = apply {
        rules.add(PricingRule("Member", setOf(CustomerType.MEMBER), MemberPricing(discountPercent), priority = 1))
    }

    fun withWholesalePricing() = apply {
        rules.add(PricingRule("Wholesale", setOf(CustomerType.WHOLESALE), WholesalePricing(), priority = 2))
    }

    fun withSeasonalPricing(seasonalRules: List<SeasonalPricing.SeasonalRule> = emptyList()) = apply {
        val seasonal = if (seasonalRules.isEmpty()) SeasonalPricing() else SeasonalPricing(rules = seasonalRules)
        rules.add(PricingRule("Seasonal", CustomerType.entries.toSet(), seasonal, priority = 0))
    }

    fun withDefaultStrategy(strategy: PricingStrategy) = apply { defaultStrategy = strategy }
    fun withFlatTax(rate: Double) = apply { taxStrategy = FlatTaxStrategy(rate) }
    fun withCategoryTax(rates: Map<String, Double>) = apply { taxStrategy = CategoryTaxStrategy(rates) }
    fun withExemptions(categories: Set<String>, rate: Double = 0.08) = apply { taxStrategy = ExemptionTaxStrategy(categories, rate) }

    fun build() = PricingEngine(rules.toList(), defaultStrategy, taxStrategy)
}
