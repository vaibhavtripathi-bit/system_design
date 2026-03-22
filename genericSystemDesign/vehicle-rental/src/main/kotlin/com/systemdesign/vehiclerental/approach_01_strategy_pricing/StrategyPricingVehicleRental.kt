package com.systemdesign.vehiclerental.approach_01_strategy_pricing

import com.systemdesign.vehiclerental.common.*
import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * Approach 1: Strategy Pattern for Pricing
 * 
 * Different pricing strategies can be swapped at runtime.
 * Supports complex pricing rules like weekend rates, long-term discounts, seasonal pricing.
 * 
 * Pattern: Strategy Pattern
 * 
 * Trade-offs:
 * + Pricing logic is completely decoupled and testable
 * + Easy to add new pricing models without changing core logic
 * + Strategies can be combined using composite pattern
 * - Need to manage multiple strategy instances
 * - Pricing calculation distributed across strategies
 * 
 * When to use:
 * - When multiple pricing models need to be supported
 * - When pricing rules may change frequently
 * - When different customer tiers have different pricing
 * 
 * Extensibility:
 * - New pricing model: Implement PricingStrategy interface
 * - Combine strategies: Use CompositePricingStrategy
 */

/** Pricing strategy interface */
interface PricingStrategy {
    fun calculatePrice(vehicle: Vehicle, period: RentalPeriod, addons: List<Addon>): PriceBreakdown
    fun getName(): String
}

/** Daily rate pricing - straightforward daily rate calculation */
class DailyRatePricing : PricingStrategy {
    
    override fun calculatePrice(vehicle: Vehicle, period: RentalPeriod, addons: List<Addon>): PriceBreakdown {
        val days = period.getDays()
        val baseCost = vehicle.dailyRate * days
        val addonsCost = addons.sumOf { it.dailyPrice * days }
        val taxes = (baseCost + addonsCost) * TAX_RATE
        
        return PriceBreakdown(
            baseCost = baseCost,
            addonsCost = addonsCost,
            taxes = taxes
        )
    }
    
    override fun getName(): String = "Daily Rate"
    
    companion object {
        private const val TAX_RATE = 0.08
    }
}

/** Weekend pricing - higher rates for weekend days */
class WeekendPricing(
    private val weekendMultiplier: Double = 1.20
) : PricingStrategy {
    
    override fun calculatePrice(vehicle: Vehicle, period: RentalPeriod, addons: List<Addon>): PriceBreakdown {
        val totalDays = period.getDays()
        val weekendDays = period.getWeekendDays()
        val weekdayDays = totalDays - weekendDays
        
        val weekdayCost = vehicle.dailyRate * weekdayDays
        val weekendCost = vehicle.dailyRate * weekendDays * weekendMultiplier
        val baseCost = weekdayCost + weekendCost
        
        val addonsCost = addons.sumOf { it.dailyPrice * totalDays }
        val taxes = (baseCost + addonsCost) * TAX_RATE
        
        return PriceBreakdown(
            baseCost = baseCost,
            addonsCost = addonsCost,
            taxes = taxes
        )
    }
    
    override fun getName(): String = "Weekend Premium"
    
    companion object {
        private const val TAX_RATE = 0.08
    }
}

/** Long-term discount pricing - discounts for weekly/monthly rentals */
class LongTermDiscountPricing(
    private val weeklyDiscountPercent: Double = 10.0,
    private val monthlyDiscountPercent: Double = 20.0
) : PricingStrategy {
    
    override fun calculatePrice(vehicle: Vehicle, period: RentalPeriod, addons: List<Addon>): PriceBreakdown {
        val days = period.getDays()
        val baseCost = vehicle.dailyRate * days
        val addonsCost = addons.sumOf { it.dailyPrice * days }
        
        val discount = when {
            days >= 30 -> (baseCost * monthlyDiscountPercent / 100)
            days >= 7 -> (baseCost * weeklyDiscountPercent / 100)
            else -> 0.0
        }
        
        val taxes = (baseCost + addonsCost - discount) * TAX_RATE
        
        return PriceBreakdown(
            baseCost = baseCost,
            addonsCost = addonsCost,
            discounts = discount,
            taxes = taxes
        )
    }
    
    override fun getName(): String = "Long-Term Discount"
    
    companion object {
        private const val TAX_RATE = 0.08
    }
}

/** Seasonal pricing - adjusts rates based on time of year */
class SeasonalPricing(
    private val seasonalRates: List<SeasonalRate> = listOf(
        SeasonalRate.SUMMER,
        SeasonalRate.WINTER_HOLIDAYS,
        SeasonalRate.SPRING_BREAK,
        SeasonalRate.OFF_PEAK
    )
) : PricingStrategy {
    
    override fun calculatePrice(vehicle: Vehicle, period: RentalPeriod, addons: List<Addon>): PriceBreakdown {
        val days = period.getDays()
        val seasonalMultiplier = getSeasonalMultiplier(period.start)
        
        val baseCost = vehicle.dailyRate * days * seasonalMultiplier
        val addonsCost = addons.sumOf { it.dailyPrice * days }
        val taxes = (baseCost + addonsCost) * TAX_RATE
        
        return PriceBreakdown(
            baseCost = baseCost,
            addonsCost = addonsCost,
            taxes = taxes
        )
    }
    
    private fun getSeasonalMultiplier(dateTime: LocalDateTime): Double {
        return seasonalRates.find { it.appliesTo(dateTime) }?.multiplier ?: 1.0
    }
    
    override fun getName(): String = "Seasonal Pricing"
    
    companion object {
        private const val TAX_RATE = 0.08
    }
}

/** Membership pricing - applies member discounts */
class MembershipPricing(
    private val baseStrategy: PricingStrategy = DailyRatePricing()
) : PricingStrategy {
    
    fun calculatePrice(
        vehicle: Vehicle, 
        period: RentalPeriod, 
        addons: List<Addon>,
        customer: Customer
    ): PriceBreakdown {
        val baseBreakdown = baseStrategy.calculatePrice(vehicle, period, addons)
        val memberDiscount = (baseBreakdown.baseCost * customer.membershipTier.discountPercent / 100)
        val totalDiscount = baseBreakdown.discounts + memberDiscount
        
        val taxableAmount = baseBreakdown.baseCost + baseBreakdown.addonsCost - totalDiscount
        val taxes = taxableAmount * TAX_RATE
        
        return PriceBreakdown(
            baseCost = baseBreakdown.baseCost,
            addonsCost = baseBreakdown.addonsCost,
            discounts = totalDiscount,
            taxes = taxes
        )
    }
    
    override fun calculatePrice(vehicle: Vehicle, period: RentalPeriod, addons: List<Addon>): PriceBreakdown {
        return baseStrategy.calculatePrice(vehicle, period, addons)
    }
    
    override fun getName(): String = "Membership Pricing"
    
    companion object {
        private const val TAX_RATE = 0.08
    }
}

/** Composite pricing - combines multiple strategies */
class CompositePricingStrategy(
    private val strategies: List<PricingStrategy>
) : PricingStrategy {
    
    override fun calculatePrice(vehicle: Vehicle, period: RentalPeriod, addons: List<Addon>): PriceBreakdown {
        if (strategies.isEmpty()) {
            return DailyRatePricing().calculatePrice(vehicle, period, addons)
        }
        
        val breakdowns = strategies.map { it.calculatePrice(vehicle, period, addons) }
        
        val avgBaseCost = breakdowns.map { it.baseCost }.average()
        val avgAddonsCost = breakdowns.map { it.addonsCost }.average()
        val maxDiscount = breakdowns.maxOf { it.discounts }
        val avgTaxes = breakdowns.map { it.taxes }.average()
        
        return PriceBreakdown(
            baseCost = avgBaseCost,
            addonsCost = avgAddonsCost,
            discounts = maxDiscount,
            taxes = avgTaxes
        )
    }
    
    override fun getName(): String = "Composite (${strategies.map { it.getName() }.joinToString(", ")})"
}

/** Pricing engine that manages pricing strategies */
class PricingEngine(
    private var defaultStrategy: PricingStrategy = DailyRatePricing()
) {
    private val strategies = mutableMapOf<VehicleType, PricingStrategy>()
    
    fun setDefaultStrategy(strategy: PricingStrategy) {
        defaultStrategy = strategy
    }
    
    fun setStrategyForVehicleType(type: VehicleType, strategy: PricingStrategy) {
        strategies[type] = strategy
    }
    
    fun calculatePrice(vehicle: Vehicle, period: RentalPeriod, addons: List<Addon>): PriceBreakdown {
        val strategy = strategies[vehicle.type] ?: defaultStrategy
        return strategy.calculatePrice(vehicle, period, addons)
    }
    
    fun calculatePriceWithMembership(
        vehicle: Vehicle, 
        period: RentalPeriod, 
        addons: List<Addon>,
        customer: Customer
    ): PriceBreakdown {
        val baseBreakdown = calculatePrice(vehicle, period, addons)
        val memberDiscount = baseBreakdown.baseCost * customer.membershipTier.discountPercent / 100
        val totalDiscount = baseBreakdown.discounts + memberDiscount
        
        val taxableAmount = baseBreakdown.baseCost + baseBreakdown.addonsCost - totalDiscount
        val taxes = taxableAmount * TAX_RATE
        
        return PriceBreakdown(
            baseCost = baseBreakdown.baseCost,
            addonsCost = baseBreakdown.addonsCost,
            discounts = totalDiscount,
            taxes = taxes
        )
    }
    
    fun calculateLateReturnFee(vehicle: Vehicle, hoursLate: Long): Double {
        if (hoursLate <= GRACE_PERIOD_HOURS) return 0.0
        
        val chargeableHours = hoursLate - GRACE_PERIOD_HOURS
        val lateDays = (chargeableHours / 24.0).coerceAtLeast(0.5)
        
        return vehicle.dailyRate * lateDays * LATE_FEE_MULTIPLIER
    }
    
    fun getQuotes(
        vehicle: Vehicle, 
        period: RentalPeriod, 
        addons: List<Addon>
    ): Map<String, PriceBreakdown> {
        val allStrategies = listOf(
            DailyRatePricing(),
            WeekendPricing(),
            LongTermDiscountPricing(),
            SeasonalPricing()
        )
        
        return allStrategies.associate { strategy ->
            strategy.getName() to strategy.calculatePrice(vehicle, period, addons)
        }
    }
    
    companion object {
        private const val TAX_RATE = 0.08
        private const val GRACE_PERIOD_HOURS = 1L
        private const val LATE_FEE_MULTIPLIER = 1.5
    }
}

/** Rental service using strategy pricing */
class StrategyPricingRentalService(
    private val pricingEngine: PricingEngine = PricingEngine()
) {
    private val rentals = mutableMapOf<String, Rental>()
    private val locations = mutableMapOf<String, Location>()
    private var rentalIdCounter = 1
    
    fun addLocation(location: Location) {
        locations[location.id] = location
    }
    
    fun createRental(
        vehicle: Vehicle,
        customer: Customer,
        period: RentalPeriod,
        addons: List<Addon> = emptyList(),
        pickupLocation: Location? = null,
        dropoffLocation: Location? = null
    ): RentalResult {
        if (!vehicle.isAvailable()) {
            return RentalResult.VehicleUnavailable(vehicle.id, "Vehicle is not available")
        }
        
        if (period.start.isAfter(period.end)) {
            return RentalResult.InvalidPeriod("Start date must be before end date")
        }
        
        val priceBreakdown = pricingEngine.calculatePriceWithMembership(
            vehicle, period, addons, customer
        )
        
        val rental = Rental(
            id = "RNT-${rentalIdCounter++}",
            vehicle = vehicle,
            customer = customer,
            period = period,
            addons = addons,
            status = RentalStatus.RESERVED,
            pickupLocation = pickupLocation,
            dropoffLocation = dropoffLocation,
            totalPrice = priceBreakdown.total()
        )
        
        vehicle.status = RentalStatus.RESERVED
        rentals[rental.id] = rental
        
        return RentalResult.Success(rental, priceBreakdown)
    }
    
    fun getQuotes(
        vehicle: Vehicle,
        period: RentalPeriod,
        addons: List<Addon>
    ): Map<String, PriceBreakdown> {
        return pricingEngine.getQuotes(vehicle, period, addons)
    }
    
    fun getRental(rentalId: String): Rental? = rentals[rentalId]
    
    fun setPricingStrategy(strategy: PricingStrategy) {
        pricingEngine.setDefaultStrategy(strategy)
    }
    
    fun setVehicleTypePricing(type: VehicleType, strategy: PricingStrategy) {
        pricingEngine.setStrategyForVehicleType(type, strategy)
    }
}
