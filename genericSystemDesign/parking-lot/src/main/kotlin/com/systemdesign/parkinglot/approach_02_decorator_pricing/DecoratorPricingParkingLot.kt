package com.systemdesign.parkinglot.approach_02_decorator_pricing

import com.systemdesign.parkinglot.common.*
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Approach 2: Decorator Pattern for Pricing
 * 
 * Pricing rules are stackable decorators that can be combined flexibly.
 * Base price + peak hour surcharge + weekend surcharge + loyalty discount.
 * 
 * Pattern: Decorator Pattern
 * 
 * Trade-offs:
 * + Pricing rules are composable and stackable
 * + Easy to add new pricing modifiers
 * + Rules can be combined differently for different customer types
 * - Order of decorators matters for some calculations
 * - Can be complex to debug the final price calculation
 * 
 * When to use:
 * - When pricing has multiple independent modifiers
 * - When promotions/discounts need to be applied dynamically
 * - When different customer tiers have different rules
 * 
 * Extensibility:
 * - New pricing rule: Create new PricingDecorator subclass
 * - Customer loyalty: Create LoyaltyDiscountDecorator
 * - Promo codes: Create PromoCodeDecorator
 */

/** Base pricing strategy */
open class BasePricingStrategy(
    private val hourlyRate: Double = 2.0,
    private val dailyMax: Double = 20.0
) : PricingStrategy {
    override fun calculateFee(ticket: ParkingTicket): Double {
        val exitTime = ticket.exitTime ?: LocalDateTime.now()
        val hours = ChronoUnit.HOURS.between(ticket.entryTime, exitTime).toInt() + 1
        val rawFee = hours * hourlyRate
        return minOf(rawFee, dailyMax)
    }
}

/** Abstract decorator for pricing */
abstract class PricingDecorator(
    protected val wrapped: PricingStrategy
) : PricingStrategy

/** Peak hour surcharge decorator */
class PeakHourPricingDecorator(
    wrapped: PricingStrategy,
    private val surchargePercent: Double = 0.25,
    private val peakHoursStart: Int = 8,
    private val peakHoursEnd: Int = 18
) : PricingDecorator(wrapped) {
    
    override fun calculateFee(ticket: ParkingTicket): Double {
        val baseFee = wrapped.calculateFee(ticket)
        
        val entryHour = ticket.entryTime.hour
        val isPeakEntry = entryHour in peakHoursStart until peakHoursEnd
        
        return if (isPeakEntry) {
            baseFee * (1 + surchargePercent)
        } else {
            baseFee
        }
    }
}

/** Weekend surcharge decorator */
class WeekendPricingDecorator(
    wrapped: PricingStrategy,
    private val surchargePercent: Double = 0.15
) : PricingDecorator(wrapped) {
    
    override fun calculateFee(ticket: ParkingTicket): Double {
        val baseFee = wrapped.calculateFee(ticket)
        
        val isWeekend = ticket.entryTime.dayOfWeek in listOf(
            DayOfWeek.SATURDAY, 
            DayOfWeek.SUNDAY
        )
        
        return if (isWeekend) {
            baseFee * (1 + surchargePercent)
        } else {
            baseFee
        }
    }
}

/** Loyalty discount decorator */
class LoyaltyDiscountDecorator(
    wrapped: PricingStrategy,
    private val discountPercent: Double = 0.10,
    private val loyaltyChecker: LoyaltyChecker
) : PricingDecorator(wrapped) {
    
    override fun calculateFee(ticket: ParkingTicket): Double {
        val baseFee = wrapped.calculateFee(ticket)
        
        return if (loyaltyChecker.isLoyalCustomer(ticket.vehicle.licensePlate)) {
            baseFee * (1 - discountPercent)
        } else {
            baseFee
        }
    }
}

/** Promo code discount decorator */
class PromoCodeDecorator(
    wrapped: PricingStrategy,
    private val promoValidator: PromoValidator
) : PricingDecorator(wrapped) {
    
    override fun calculateFee(ticket: ParkingTicket): Double {
        val baseFee = wrapped.calculateFee(ticket)
        val discount = promoValidator.getDiscount(ticket.ticketId)
        return baseFee * (1 - discount)
    }
}

/** Lost ticket penalty decorator */
class LostTicketDecorator(
    wrapped: PricingStrategy,
    private val penalty: Double = 50.0
) : PricingDecorator(wrapped) {
    
    override fun calculateFee(ticket: ParkingTicket): Double {
        return if (ticket.isLost) {
            penalty
        } else {
            wrapped.calculateFee(ticket)
        }
    }
}

/** Spot type surcharge decorator (e.g., EV spots cost more) */
class SpotTypePricingDecorator(
    wrapped: PricingStrategy,
    private val spotSurcharges: Map<SpotType, Double> = mapOf(
        SpotType.ELECTRIC to 0.20,
        SpotType.HANDICAPPED to 0.0
    )
) : PricingDecorator(wrapped) {
    
    override fun calculateFee(ticket: ParkingTicket): Double {
        val baseFee = wrapped.calculateFee(ticket)
        val surcharge = spotSurcharges[ticket.spot.type] ?: 0.0
        return baseFee * (1 + surcharge)
    }
}

/** First hour free decorator */
class FirstHourFreeDecorator(
    wrapped: PricingStrategy
) : PricingDecorator(wrapped) {
    
    override fun calculateFee(ticket: ParkingTicket): Double {
        val exitTime = ticket.exitTime ?: LocalDateTime.now()
        val minutes = ChronoUnit.MINUTES.between(ticket.entryTime, exitTime)
        
        return if (minutes <= 60) {
            0.0
        } else {
            wrapped.calculateFee(ticket)
        }
    }
}

/** Interface for loyalty checking */
interface LoyaltyChecker {
    fun isLoyalCustomer(licensePlate: String): Boolean
}

/** Interface for promo validation */
interface PromoValidator {
    fun getDiscount(ticketId: String): Double
}

/** Default implementations */
class SimpleLoyaltyChecker(
    private val loyalCustomers: Set<String> = emptySet()
) : LoyaltyChecker {
    override fun isLoyalCustomer(licensePlate: String): Boolean {
        return licensePlate in loyalCustomers
    }
}

class SimplePromoValidator(
    private val promos: MutableMap<String, Double> = mutableMapOf()
) : PromoValidator {
    fun applyPromo(ticketId: String, discount: Double) {
        promos[ticketId] = discount
    }
    
    override fun getDiscount(ticketId: String): Double {
        return promos[ticketId] ?: 0.0
    }
}

/**
 * Builder for constructing complex pricing strategies
 */
class PricingStrategyBuilder {
    private var strategy: PricingStrategy = BasePricingStrategy()
    
    fun withBase(hourlyRate: Double, dailyMax: Double): PricingStrategyBuilder {
        strategy = BasePricingStrategy(hourlyRate, dailyMax)
        return this
    }
    
    fun withPeakHours(
        surchargePercent: Double = 0.25,
        startHour: Int = 8,
        endHour: Int = 18
    ): PricingStrategyBuilder {
        strategy = PeakHourPricingDecorator(strategy, surchargePercent, startHour, endHour)
        return this
    }
    
    fun withWeekendSurcharge(surchargePercent: Double = 0.15): PricingStrategyBuilder {
        strategy = WeekendPricingDecorator(strategy, surchargePercent)
        return this
    }
    
    fun withLoyaltyDiscount(
        discountPercent: Double = 0.10,
        loyaltyChecker: LoyaltyChecker
    ): PricingStrategyBuilder {
        strategy = LoyaltyDiscountDecorator(strategy, discountPercent, loyaltyChecker)
        return this
    }
    
    fun withLostTicketPenalty(penalty: Double = 50.0): PricingStrategyBuilder {
        strategy = LostTicketDecorator(strategy, penalty)
        return this
    }
    
    fun withSpotTypeSurcharges(surcharges: Map<SpotType, Double>): PricingStrategyBuilder {
        strategy = SpotTypePricingDecorator(strategy, surcharges)
        return this
    }
    
    fun withFirstHourFree(): PricingStrategyBuilder {
        strategy = FirstHourFreeDecorator(strategy)
        return this
    }
    
    fun build(): PricingStrategy = strategy
}
