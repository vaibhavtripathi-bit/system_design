package com.systemdesign.ridesharing.common

import java.time.LocalDateTime
import java.util.UUID
import kotlin.math.*

/**
 * Core domain models for Ride-Sharing System.
 * 
 * Extensibility Points:
 * - New ride types: Add to RideType enum and update pricing/matching strategies
 * - New ride states: Add to RideState enum and update state machine transitions
 * - New matching strategies: Implement DriverMatchingStrategy interface
 * - New pricing rules: Implement PricingDecorator
 * - New events: Add to RideEvent sealed class
 * 
 * Breaking Changes Required For:
 * - Changing ride ID structure (requires migration)
 * - Adding multi-region support (requires location/zone relationship changes)
 */

/** Geographic location */
data class Location(
    val lat: Double,
    val lng: Double
) {
    fun distanceTo(other: Location): Double {
        val earthRadius = 6371.0 // km
        val dLat = Math.toRadians(other.lat - lat)
        val dLng = Math.toRadians(other.lng - lng)
        val a = sin(dLat / 2).pow(2) + 
                cos(Math.toRadians(lat)) * cos(Math.toRadians(other.lat)) * 
                sin(dLng / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }
    
    fun isWithinRadius(other: Location, radiusKm: Double): Boolean {
        return distanceTo(other) <= radiusKm
    }
}

/** Types of rides available */
enum class RideType(val baseMultiplier: Double, val minSeats: Int) {
    STANDARD(1.0, 1),
    PREMIUM(1.5, 1),
    POOL(0.7, 1),
    XL(1.3, 4);
    
    companion object {
        fun fromVehicleCapacity(capacity: Int): List<RideType> {
            return entries.filter { it.minSeats <= capacity }
        }
    }
}

/** Ride lifecycle states */
enum class RideState {
    REQUESTED,
    MATCHING,
    DRIVER_ASSIGNED,
    ARRIVED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED;
    
    fun isTerminal(): Boolean = this == COMPLETED || this == CANCELLED
    fun isActive(): Boolean = !isTerminal()
}

/** Cancellation reasons */
enum class CancellationReason {
    RIDER_CANCELLED,
    DRIVER_CANCELLED,
    NO_DRIVERS_AVAILABLE,
    DRIVER_NO_SHOW,
    RIDER_NO_SHOW,
    PAYMENT_FAILED,
    SYSTEM_ERROR,
    TIMEOUT
}

/** Vehicle types for drivers */
enum class VehicleType(val capacity: Int, val supportedRideTypes: Set<RideType>) {
    SEDAN(4, setOf(RideType.STANDARD, RideType.POOL)),
    SUV(6, setOf(RideType.STANDARD, RideType.POOL, RideType.XL)),
    LUXURY(4, setOf(RideType.PREMIUM)),
    VAN(7, setOf(RideType.XL, RideType.POOL))
}

/** Represents a driver */
data class Driver(
    val id: String,
    val name: String,
    val location: Location,
    val rating: Double,
    val isAvailable: Boolean,
    val vehicleType: VehicleType,
    val totalRides: Int = 0,
    val acceptanceRate: Double = 1.0
) {
    fun canAccept(rideType: RideType): Boolean {
        return isAvailable && vehicleType.supportedRideTypes.contains(rideType)
    }
    
    fun withLocation(newLocation: Location): Driver = copy(location = newLocation)
    fun withAvailability(available: Boolean): Driver = copy(isAvailable = available)
}

/** Represents a rider/passenger */
data class Rider(
    val id: String,
    val name: String,
    val rating: Double,
    val totalRides: Int = 0,
    val paymentMethodId: String? = null
) {
    fun hasValidPayment(): Boolean = paymentMethodId != null
}

/** A ride request from a rider */
data class RideRequest(
    val id: String = UUID.randomUUID().toString(),
    val rider: Rider,
    val pickup: Location,
    val dropoff: Location,
    val rideType: RideType,
    val requestTime: LocalDateTime = LocalDateTime.now(),
    val scheduledTime: LocalDateTime? = null,
    val notes: String? = null
) {
    fun isScheduled(): Boolean = scheduledTime != null
    
    fun estimatedDistance(): Double = pickup.distanceTo(dropoff)
}

/** Route information */
data class Route(
    val waypoints: List<Location>,
    val distanceKm: Double,
    val estimatedDurationMinutes: Int,
    val polyline: String? = null
) {
    companion object {
        fun direct(pickup: Location, dropoff: Location): Route {
            val distance = pickup.distanceTo(dropoff)
            val duration = (distance / 40 * 60).toInt() // Assuming 40 km/h average
            return Route(
                waypoints = listOf(pickup, dropoff),
                distanceKm = distance,
                estimatedDurationMinutes = duration
            )
        }
    }
}

/** A complete ride with all details */
data class Ride(
    val id: String = UUID.randomUUID().toString(),
    val request: RideRequest,
    val driver: Driver? = null,
    val state: RideState = RideState.REQUESTED,
    val startTime: LocalDateTime? = null,
    val endTime: LocalDateTime? = null,
    val price: Price? = null,
    val route: Route? = null,
    val cancellation: Cancellation? = null,
    val matchAttempts: Int = 0
) {
    fun isCompleted(): Boolean = state == RideState.COMPLETED
    fun isCancelled(): Boolean = state == RideState.CANCELLED
    fun hasDriver(): Boolean = driver != null
    
    fun withState(newState: RideState): Ride = copy(state = newState)
    fun withDriver(newDriver: Driver): Ride = copy(driver = newDriver)
}

/** Cancellation details */
data class Cancellation(
    val reason: CancellationReason,
    val cancelledBy: String, // riderId or driverId
    val cancelledAt: LocalDateTime = LocalDateTime.now(),
    val cancellationFee: Double = 0.0
)

/** Price breakdown for a ride */
data class Price(
    val baseFare: Double,
    val distanceCharge: Double,
    val timeCharge: Double,
    val surgeFee: Double = 0.0,
    val tollFee: Double = 0.0,
    val promoDiscount: Double = 0.0,
    val bookingFee: Double = 2.0,
    val currency: String = "USD"
) {
    val subtotal: Double
        get() = baseFare + distanceCharge + timeCharge + surgeFee + tollFee + bookingFee
    
    val total: Double
        get() = maxOf(0.0, subtotal - promoDiscount)
    
    fun withSurge(multiplier: Double): Price {
        val surgeAmount = (baseFare + distanceCharge + timeCharge) * (multiplier - 1)
        return copy(surgeFee = surgeAmount)
    }
    
    fun withToll(amount: Double): Price = copy(tollFee = tollFee + amount)
    
    fun withPromo(discount: Double): Price = copy(promoDiscount = discount)
}

/** Surge pricing zone */
data class SurgeZone(
    val zoneId: String,
    val center: Location,
    val radiusKm: Double,
    val multiplier: Double,
    val expiresAt: LocalDateTime
) {
    fun contains(location: Location): Boolean {
        return center.isWithinRadius(location, radiusKm)
    }
    
    fun isActive(): Boolean = LocalDateTime.now().isBefore(expiresAt)
}

/** Result of driver matching */
data class MatchResult(
    val driver: Driver,
    val etaMinutes: Int,
    val distance: Double,
    val score: Double
) {
    companion object {
        fun fromDriverAndPickup(driver: Driver, pickup: Location): MatchResult {
            val distance = driver.location.distanceTo(pickup)
            val eta = (distance / 30 * 60).toInt() // Assuming 30 km/h in city
            val score = calculateScore(driver, distance)
            return MatchResult(driver, eta, distance, score)
        }
        
        private fun calculateScore(driver: Driver, distance: Double): Double {
            val distanceScore = 100 - (distance * 10) // Closer is better
            val ratingScore = driver.rating * 10
            val acceptanceScore = driver.acceptanceRate * 20
            return distanceScore + ratingScore + acceptanceScore
        }
    }
}

/** Toll road segment */
data class TollSegment(
    val segmentId: String,
    val startLocation: Location,
    val endLocation: Location,
    val tollAmount: Double,
    val name: String
)

/** Promo code for discounts */
data class PromoCode(
    val code: String,
    val discountType: DiscountType,
    val value: Double,
    val maxDiscount: Double? = null,
    val minRideAmount: Double = 0.0,
    val validUntil: LocalDateTime,
    val usageLimit: Int? = null,
    val usedCount: Int = 0
) {
    enum class DiscountType {
        PERCENTAGE, FIXED_AMOUNT
    }
    
    fun isValid(): Boolean {
        val notExpired = LocalDateTime.now().isBefore(validUntil)
        val hasUsagesLeft = usageLimit == null || usedCount < usageLimit
        return notExpired && hasUsagesLeft
    }
    
    fun calculateDiscount(amount: Double): Double {
        if (amount < minRideAmount) return 0.0
        
        val discount = when (discountType) {
            DiscountType.PERCENTAGE -> amount * (value / 100)
            DiscountType.FIXED_AMOUNT -> value
        }
        
        return maxDiscount?.let { minOf(discount, it) } ?: discount
    }
}

/** Events for ride state changes - used by observers */
sealed class RideEvent {
    abstract val rideId: String
    abstract val timestamp: LocalDateTime
    
    data class RideRequested(
        override val rideId: String,
        val request: RideRequest,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : RideEvent()
    
    data class MatchingStarted(
        override val rideId: String,
        val searchRadius: Double,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : RideEvent()
    
    data class DriverAssigned(
        override val rideId: String,
        val driver: Driver,
        val eta: Int,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : RideEvent()
    
    data class DriverArrived(
        override val rideId: String,
        val driverId: String,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : RideEvent()
    
    data class RideStarted(
        override val rideId: String,
        val startLocation: Location,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : RideEvent()
    
    data class RideCompleted(
        override val rideId: String,
        val finalPrice: Price,
        val route: Route,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : RideEvent()
    
    data class RideCancelled(
        override val rideId: String,
        val reason: CancellationReason,
        val cancelledBy: String,
        val fee: Double,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : RideEvent()
    
    data class DriverLocationUpdated(
        override val rideId: String,
        val driverId: String,
        val newLocation: Location,
        val etaMinutes: Int,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : RideEvent()
    
    data class MatchingTimeout(
        override val rideId: String,
        val attempts: Int,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : RideEvent()
}

/** Observer interface for ride events */
interface RideObserver {
    fun onRideEvent(event: RideEvent)
}

/** Strategy interface for driver matching */
interface DriverMatchingStrategy {
    fun findBestMatch(
        request: RideRequest,
        availableDrivers: List<Driver>,
        surgeZones: List<SurgeZone> = emptyList()
    ): MatchResult?
}

/** Strategy interface for pricing calculation */
interface PricingStrategy {
    fun calculatePrice(ride: Ride): Price
}

/** Cancellation policy interface */
interface CancellationPolicy {
    fun canCancel(ride: Ride, cancelledBy: String): Boolean
    fun getCancellationFee(ride: Ride, cancelledBy: String): Double
}

/** Driver availability statistics */
data class DriverStats(
    val availableDrivers: Int,
    val totalDrivers: Int,
    val averageEta: Int,
    val surgeMultiplier: Double
)

/** Ride history for a user */
data class RideHistory(
    val rides: List<Ride>,
    val totalRides: Int,
    val totalSpent: Double,
    val averageRating: Double
)
