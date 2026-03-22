package com.systemdesign.ridesharing.approach_03_decorator_pricing

import com.systemdesign.ridesharing.common.*
import java.time.DayOfWeek
import java.time.LocalDateTime
import kotlin.math.roundToInt

/**
 * Approach 3: Decorator Pattern for Pricing
 * 
 * Pricing rules are stackable decorators that can be combined flexibly.
 * Base price + surge + tolls - promo = final price.
 * 
 * Pattern: Decorator Pattern
 * 
 * Trade-offs:
 * + Pricing rules are composable and stackable
 * + Easy to add new pricing modifiers (seasonal, events, loyalty)
 * + Rules can be combined differently for different ride types
 * + Order of decorators matters - discounts applied last
 * - Can be complex to debug final price calculation
 * - Need to document decorator ordering requirements
 * 
 * When to use:
 * - When pricing has multiple independent modifiers
 * - When promotions/discounts need to be applied dynamically
 * - When different customer tiers have different pricing rules
 * 
 * Extensibility:
 * - New pricing modifier: Create new PricingDecorator subclass
 * - Customer loyalty: Create LoyaltyDiscountDecorator
 * - Event pricing: Create EventSurchargeDecorator
 * - Subscription: Create SubscriptionDiscountDecorator
 */

/** Base pricing calculator - foundation for all pricing */
open class BasePricingStrategy(
    private val baseFare: Double = 3.0,
    private val perKmRate: Double = 1.5,
    private val perMinuteRate: Double = 0.20,
    private val bookingFee: Double = 2.0,
    private val minimumFare: Double = 5.0
) : PricingStrategy {
    
    override fun calculatePrice(ride: Ride): Price {
        val distance = ride.route?.distanceKm ?: ride.request.estimatedDistance()
        val duration = ride.route?.estimatedDurationMinutes ?: estimateDuration(distance)
        
        val rideTypeMultiplier = ride.request.rideType.baseMultiplier
        
        val adjustedBaseFare = baseFare * rideTypeMultiplier
        val distanceCharge = distance * perKmRate * rideTypeMultiplier
        val timeCharge = duration * perMinuteRate
        
        val subtotal = adjustedBaseFare + distanceCharge + timeCharge + bookingFee
        val finalBaseFare = if (subtotal < minimumFare) {
            minimumFare - distanceCharge - timeCharge - bookingFee
        } else {
            adjustedBaseFare
        }
        
        return Price(
            baseFare = finalBaseFare,
            distanceCharge = distanceCharge,
            timeCharge = timeCharge,
            bookingFee = bookingFee
        )
    }
    
    private fun estimateDuration(distanceKm: Double): Int {
        return (distanceKm / 30 * 60).roundToInt() // 30 km/h average
    }
}

/** Abstract decorator for pricing modifications */
abstract class PricingDecorator(
    protected val wrapped: PricingStrategy
) : PricingStrategy

/**
 * Surge pricing decorator - applies dynamic pricing multiplier
 */
class SurgePricingDecorator(
    wrapped: PricingStrategy,
    private val surgeProvider: SurgeProvider
) : PricingDecorator(wrapped) {
    
    override fun calculatePrice(ride: Ride): Price {
        val basePrice = wrapped.calculatePrice(ride)
        val multiplier = surgeProvider.getSurgeMultiplier(ride.request.pickup)
        
        return if (multiplier > 1.0) {
            basePrice.withSurge(multiplier)
        } else {
            basePrice
        }
    }
}

/** Interface for surge pricing data */
interface SurgeProvider {
    fun getSurgeMultiplier(location: Location): Double
}

/** Simple implementation with configurable zones */
class ZoneSurgeProvider(
    private val zones: List<SurgeZone> = emptyList()
) : SurgeProvider {
    override fun getSurgeMultiplier(location: Location): Double {
        return zones
            .filter { it.isActive() && it.contains(location) }
            .maxOfOrNull { it.multiplier } ?: 1.0
    }
}

/** Fixed surge for testing */
class FixedSurgeProvider(private val multiplier: Double) : SurgeProvider {
    override fun getSurgeMultiplier(location: Location): Double = multiplier
}

/**
 * Toll road decorator - adds toll charges for routes using toll roads
 */
class TollDecorator(
    wrapped: PricingStrategy,
    private val tollRegistry: TollRegistry
) : PricingDecorator(wrapped) {
    
    override fun calculatePrice(ride: Ride): Price {
        val basePrice = wrapped.calculatePrice(ride)
        
        val route = ride.route ?: return basePrice
        val tollAmount = tollRegistry.calculateTolls(route)
        
        return if (tollAmount > 0) {
            basePrice.withToll(tollAmount)
        } else {
            basePrice
        }
    }
}

/** Interface for toll calculation */
interface TollRegistry {
    fun calculateTolls(route: Route): Double
}

/** Simple toll registry implementation */
class SimpleTollRegistry(
    private val tollSegments: List<TollSegment> = emptyList()
) : TollRegistry {
    
    override fun calculateTolls(route: Route): Double {
        if (route.waypoints.size < 2) return 0.0
        
        return tollSegments
            .filter { segment -> routeIntersectsSegment(route, segment) }
            .sumOf { it.tollAmount }
    }
    
    private fun routeIntersectsSegment(route: Route, segment: TollSegment): Boolean {
        // Simplified: check if route passes near toll segment endpoints
        return route.waypoints.any { waypoint ->
            waypoint.isWithinRadius(segment.startLocation, 0.5) ||
            waypoint.isWithinRadius(segment.endLocation, 0.5)
        }
    }
}

/**
 * Promo code discount decorator - applies promotional discounts
 */
class PromoCodeDecorator(
    wrapped: PricingStrategy,
    private val promoValidator: PromoValidator
) : PricingDecorator(wrapped) {
    
    override fun calculatePrice(ride: Ride): Price {
        val basePrice = wrapped.calculatePrice(ride)
        val promo = promoValidator.getActivePromo(ride.request.rider.id, ride.id)
        
        return if (promo != null && promo.isValid()) {
            val discount = promo.calculateDiscount(basePrice.subtotal)
            basePrice.withPromo(discount)
        } else {
            basePrice
        }
    }
}

/** Interface for promo validation */
interface PromoValidator {
    fun getActivePromo(riderId: String, rideId: String): PromoCode?
    fun applyPromo(riderId: String, rideId: String, promoCode: String): Boolean
}

/** Simple promo validator implementation */
class SimplePromoValidator : PromoValidator {
    private val appliedPromos = mutableMapOf<String, PromoCode>()
    private val availablePromos = mutableMapOf<String, PromoCode>()
    
    fun registerPromo(promo: PromoCode) {
        availablePromos[promo.code] = promo
    }
    
    override fun applyPromo(riderId: String, rideId: String, promoCode: String): Boolean {
        val promo = availablePromos[promoCode] ?: return false
        if (!promo.isValid()) return false
        
        appliedPromos["$riderId:$rideId"] = promo
        return true
    }
    
    override fun getActivePromo(riderId: String, rideId: String): PromoCode? {
        return appliedPromos["$riderId:$rideId"]
    }
}

/**
 * Peak hour surcharge decorator - higher prices during rush hours
 */
class PeakHourDecorator(
    wrapped: PricingStrategy,
    private val surchargePercent: Double = 0.15,
    private val morningPeakStart: Int = 7,
    private val morningPeakEnd: Int = 10,
    private val eveningPeakStart: Int = 17,
    private val eveningPeakEnd: Int = 20
) : PricingDecorator(wrapped) {
    
    override fun calculatePrice(ride: Ride): Price {
        val basePrice = wrapped.calculatePrice(ride)
        
        val hour = ride.request.requestTime.hour
        val isPeakHour = hour in morningPeakStart until morningPeakEnd ||
                         hour in eveningPeakStart until eveningPeakEnd
        
        return if (isPeakHour) {
            val surcharge = (basePrice.baseFare + basePrice.distanceCharge) * surchargePercent
            basePrice.copy(surgeFee = basePrice.surgeFee + surcharge)
        } else {
            basePrice
        }
    }
}

/**
 * Weekend pricing decorator - different rates on weekends
 */
class WeekendPricingDecorator(
    wrapped: PricingStrategy,
    private val surchargePercent: Double = 0.10
) : PricingDecorator(wrapped) {
    
    override fun calculatePrice(ride: Ride): Price {
        val basePrice = wrapped.calculatePrice(ride)
        
        val isWeekend = ride.request.requestTime.dayOfWeek in listOf(
            DayOfWeek.SATURDAY,
            DayOfWeek.SUNDAY
        )
        
        return if (isWeekend) {
            val surcharge = (basePrice.baseFare + basePrice.distanceCharge) * surchargePercent
            basePrice.copy(surgeFee = basePrice.surgeFee + surcharge)
        } else {
            basePrice
        }
    }
}

/**
 * Night surcharge decorator - late night pricing
 */
class NightSurchargeDecorator(
    wrapped: PricingStrategy,
    private val surchargePercent: Double = 0.20,
    private val nightStart: Int = 22,
    private val nightEnd: Int = 5
) : PricingDecorator(wrapped) {
    
    override fun calculatePrice(ride: Ride): Price {
        val basePrice = wrapped.calculatePrice(ride)
        
        val hour = ride.request.requestTime.hour
        val isNight = hour >= nightStart || hour < nightEnd
        
        return if (isNight) {
            val surcharge = (basePrice.baseFare + basePrice.distanceCharge) * surchargePercent
            basePrice.copy(surgeFee = basePrice.surgeFee + surcharge)
        } else {
            basePrice
        }
    }
}

/**
 * Loyalty discount decorator - discounts for frequent riders
 */
class LoyaltyDiscountDecorator(
    wrapped: PricingStrategy,
    private val loyaltyChecker: LoyaltyChecker
) : PricingDecorator(wrapped) {
    
    override fun calculatePrice(ride: Ride): Price {
        val basePrice = wrapped.calculatePrice(ride)
        
        val tier = loyaltyChecker.getLoyaltyTier(ride.request.rider.id)
        val discount = tier.discountPercent * basePrice.subtotal
        
        return basePrice.withPromo(basePrice.promoDiscount + discount)
    }
}

/** Loyalty tier definitions */
enum class LoyaltyTier(val minRides: Int, val discountPercent: Double) {
    BRONZE(0, 0.0),
    SILVER(10, 0.05),
    GOLD(50, 0.10),
    PLATINUM(100, 0.15)
}

/** Interface for loyalty checking */
interface LoyaltyChecker {
    fun getLoyaltyTier(riderId: String): LoyaltyTier
}

/** Simple loyalty checker implementation */
class SimpleLoyaltyChecker(
    private val riderRideCounts: Map<String, Int> = emptyMap()
) : LoyaltyChecker {
    
    override fun getLoyaltyTier(riderId: String): LoyaltyTier {
        val rideCount = riderRideCounts[riderId] ?: 0
        return LoyaltyTier.entries
            .filter { rideCount >= it.minRides }
            .maxByOrNull { it.minRides } ?: LoyaltyTier.BRONZE
    }
}

/**
 * Minimum fare decorator - ensures minimum charge
 */
class MinimumFareDecorator(
    wrapped: PricingStrategy,
    private val minimumByRideType: Map<RideType, Double> = mapOf(
        RideType.STANDARD to 5.0,
        RideType.PREMIUM to 10.0,
        RideType.POOL to 4.0,
        RideType.XL to 8.0
    )
) : PricingDecorator(wrapped) {
    
    override fun calculatePrice(ride: Ride): Price {
        val basePrice = wrapped.calculatePrice(ride)
        val minimum = minimumByRideType[ride.request.rideType] ?: 5.0
        
        return if (basePrice.total < minimum) {
            val adjustment = minimum - basePrice.total
            basePrice.copy(baseFare = basePrice.baseFare + adjustment)
        } else {
            basePrice
        }
    }
}

/**
 * Airport surcharge decorator - extra fee for airport pickups/dropoffs
 */
class AirportSurchargeDecorator(
    wrapped: PricingStrategy,
    private val airportLocations: List<Location>,
    private val surcharge: Double = 5.0,
    private val radiusKm: Double = 2.0
) : PricingDecorator(wrapped) {
    
    override fun calculatePrice(ride: Ride): Price {
        val basePrice = wrapped.calculatePrice(ride)
        
        val isAirportPickup = airportLocations.any { airport ->
            ride.request.pickup.isWithinRadius(airport, radiusKm)
        }
        val isAirportDropoff = airportLocations.any { airport ->
            ride.request.dropoff.isWithinRadius(airport, radiusKm)
        }
        
        val totalSurcharge = when {
            isAirportPickup && isAirportDropoff -> surcharge * 2
            isAirportPickup || isAirportDropoff -> surcharge
            else -> 0.0
        }
        
        return if (totalSurcharge > 0) {
            basePrice.copy(tollFee = basePrice.tollFee + totalSurcharge)
        } else {
            basePrice
        }
    }
}

/**
 * Builder for constructing complex pricing strategies
 */
class PricingStrategyBuilder {
    private var strategy: PricingStrategy = BasePricingStrategy()
    
    fun withBase(
        baseFare: Double = 3.0,
        perKmRate: Double = 1.5,
        perMinuteRate: Double = 0.20,
        bookingFee: Double = 2.0
    ): PricingStrategyBuilder {
        strategy = BasePricingStrategy(baseFare, perKmRate, perMinuteRate, bookingFee)
        return this
    }
    
    fun withSurge(surgeProvider: SurgeProvider): PricingStrategyBuilder {
        strategy = SurgePricingDecorator(strategy, surgeProvider)
        return this
    }
    
    fun withSurgeMultiplier(multiplier: Double): PricingStrategyBuilder {
        strategy = SurgePricingDecorator(strategy, FixedSurgeProvider(multiplier))
        return this
    }
    
    fun withTolls(tollRegistry: TollRegistry): PricingStrategyBuilder {
        strategy = TollDecorator(strategy, tollRegistry)
        return this
    }
    
    fun withPromos(promoValidator: PromoValidator): PricingStrategyBuilder {
        strategy = PromoCodeDecorator(strategy, promoValidator)
        return this
    }
    
    fun withPeakHours(
        surchargePercent: Double = 0.15,
        morningStart: Int = 7,
        morningEnd: Int = 10,
        eveningStart: Int = 17,
        eveningEnd: Int = 20
    ): PricingStrategyBuilder {
        strategy = PeakHourDecorator(
            strategy, surchargePercent, 
            morningStart, morningEnd, 
            eveningStart, eveningEnd
        )
        return this
    }
    
    fun withWeekendPricing(surchargePercent: Double = 0.10): PricingStrategyBuilder {
        strategy = WeekendPricingDecorator(strategy, surchargePercent)
        return this
    }
    
    fun withNightSurcharge(
        surchargePercent: Double = 0.20,
        nightStart: Int = 22,
        nightEnd: Int = 5
    ): PricingStrategyBuilder {
        strategy = NightSurchargeDecorator(strategy, surchargePercent, nightStart, nightEnd)
        return this
    }
    
    fun withLoyalty(loyaltyChecker: LoyaltyChecker): PricingStrategyBuilder {
        strategy = LoyaltyDiscountDecorator(strategy, loyaltyChecker)
        return this
    }
    
    fun withMinimumFare(minimumByType: Map<RideType, Double>): PricingStrategyBuilder {
        strategy = MinimumFareDecorator(strategy, minimumByType)
        return this
    }
    
    fun withAirportSurcharge(
        airportLocations: List<Location>,
        surcharge: Double = 5.0
    ): PricingStrategyBuilder {
        strategy = AirportSurchargeDecorator(strategy, airportLocations, surcharge)
        return this
    }
    
    fun build(): PricingStrategy = strategy
}

/**
 * Price estimator service for showing fare estimates before booking
 */
class PriceEstimator(
    private val pricingStrategy: PricingStrategy,
    private val surgeProvider: SurgeProvider
) {
    fun estimatePrice(
        pickup: Location,
        dropoff: Location,
        rideType: RideType
    ): PriceEstimate {
        val distance = pickup.distanceTo(dropoff)
        val duration = (distance / 30 * 60).toInt()
        
        val mockRider = Rider("estimate", "Estimate User", 5.0)
        val mockRequest = RideRequest(
            rider = mockRider,
            pickup = pickup,
            dropoff = dropoff,
            rideType = rideType
        )
        val mockRide = Ride(
            request = mockRequest,
            route = Route.direct(pickup, dropoff)
        )
        
        val price = pricingStrategy.calculatePrice(mockRide)
        val surgeMultiplier = surgeProvider.getSurgeMultiplier(pickup)
        
        return PriceEstimate(
            minPrice = price.total * 0.9,
            maxPrice = price.total * 1.1,
            estimatedPrice = price.total,
            surgeMultiplier = surgeMultiplier,
            estimatedDurationMinutes = duration,
            distanceKm = distance,
            breakdown = price
        )
    }
}

/** Price estimate result */
data class PriceEstimate(
    val minPrice: Double,
    val maxPrice: Double,
    val estimatedPrice: Double,
    val surgeMultiplier: Double,
    val estimatedDurationMinutes: Int,
    val distanceKm: Double,
    val breakdown: Price
)
