package com.systemdesign.vehiclerental.common

import java.time.LocalDateTime
import java.time.Duration
import java.time.DayOfWeek

/**
 * Core domain models for Vehicle Rental System.
 * 
 * Extensibility Points:
 * - New vehicle types: Add to VehicleType enum
 * - New addons: Create Addon instances (data-driven)
 * - New pricing strategies: Implement PricingStrategy interface
 * 
 * Breaking Changes Required For:
 * - Changing rental state machine structure
 * - Multi-currency support (requires redesign)
 */

/** Vehicle categories available for rental */
enum class VehicleType {
    ECONOMY,
    COMPACT,
    MIDSIZE,
    SUV,
    LUXURY,
    VAN,
    MOTORCYCLE
}

/** Rental lifecycle states */
enum class RentalStatus {
    AVAILABLE,
    RESERVED,
    PICKED_UP,
    RETURNED,
    UNDER_MAINTENANCE,
    RETIRED
}

/** Fuel types */
enum class FuelType {
    GASOLINE,
    DIESEL,
    ELECTRIC,
    HYBRID
}

/** Vehicle in the rental fleet */
data class Vehicle(
    val id: String,
    val make: String,
    val model: String,
    val year: Int,
    val type: VehicleType,
    var status: RentalStatus = RentalStatus.AVAILABLE,
    val dailyRate: Double,
    val fuelType: FuelType = FuelType.GASOLINE,
    val passengerCapacity: Int = 5,
    val features: Set<String> = emptySet(),
    val licensePlate: String = "",
    val mileage: Int = 0
) {
    fun isAvailable(): Boolean = status == RentalStatus.AVAILABLE
    fun isRentable(): Boolean = status in setOf(RentalStatus.AVAILABLE, RentalStatus.RESERVED)
}

/** Customer renting a vehicle */
data class Customer(
    val id: String,
    val name: String,
    val license: String,
    val email: String,
    val phone: String = "",
    val membershipTier: MembershipTier = MembershipTier.STANDARD
)

/** Membership tiers for discounts */
enum class MembershipTier(val discountPercent: Double) {
    STANDARD(0.0),
    SILVER(5.0),
    GOLD(10.0),
    PLATINUM(15.0)
}

/** Rental time period */
data class RentalPeriod(
    val start: LocalDateTime,
    val end: LocalDateTime
) {
    fun getDays(): Long = Duration.between(start, end).toDays().coerceAtLeast(1)
    
    fun getWeekendDays(): Int {
        var count = 0
        var current = start.toLocalDate()
        val endDate = end.toLocalDate()
        while (!current.isAfter(endDate)) {
            if (current.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) {
                count++
            }
            current = current.plusDays(1)
        }
        return count
    }
    
    fun isLongTerm(): Boolean = getDays() >= 7
    fun isWeekend(): Boolean = getWeekendDays() > 0
}

/** Rental addon (insurance, GPS, etc.) */
data class Addon(
    val id: String,
    val name: String,
    val dailyPrice: Double,
    val description: String = ""
) {
    companion object {
        val INSURANCE = Addon("INS", "Full Insurance", 15.0, "Comprehensive damage coverage")
        val GPS = Addon("GPS", "GPS Navigation", 5.0, "Built-in GPS navigation system")
        val CHILD_SEAT = Addon("CHILD", "Child Seat", 8.0, "Safety-certified child seat")
        val ADDITIONAL_DRIVER = Addon("DRIVER", "Additional Driver", 10.0, "Second authorized driver")
        val ROADSIDE_ASSISTANCE = Addon("ROAD", "Roadside Assistance", 7.0, "24/7 emergency support")
        val WIFI_HOTSPOT = Addon("WIFI", "WiFi Hotspot", 6.0, "Mobile internet connectivity")
    }
}

/** A rental booking */
data class Rental(
    val id: String,
    val vehicle: Vehicle,
    val customer: Customer,
    val period: RentalPeriod,
    val addons: List<Addon> = emptyList(),
    var status: RentalStatus = RentalStatus.RESERVED,
    val pickupLocation: Location? = null,
    val dropoffLocation: Location? = null,
    val totalPrice: Double = 0.0,
    val actualReturnTime: LocalDateTime? = null,
    val damageAssessment: DamageAssessment? = null
)

/** Rental location/branch */
data class Location(
    val id: String,
    val name: String,
    val address: String,
    val vehicles: MutableList<Vehicle> = mutableListOf(),
    val operatingHours: OperatingHours = OperatingHours.DEFAULT
) {
    fun getAvailableVehicles(): List<Vehicle> = vehicles.filter { it.isAvailable() }
    fun getAvailableVehiclesByType(type: VehicleType): List<Vehicle> = 
        vehicles.filter { it.isAvailable() && it.type == type }
}

/** Operating hours for a location */
data class OperatingHours(
    val openTime: Int = 8,
    val closeTime: Int = 20
) {
    companion object {
        val DEFAULT = OperatingHours(8, 20)
        val EXTENDED = OperatingHours(6, 22)
        val TWENTY_FOUR_HOURS = OperatingHours(0, 24)
    }
    
    fun isOpen(hour: Int): Boolean = hour in openTime until closeTime
}

/** Damage assessment after return */
data class DamageAssessment(
    val hasDamage: Boolean = false,
    val description: String = "",
    val repairCost: Double = 0.0,
    val photos: List<String> = emptyList()
)

/** Price breakdown for a rental */
data class PriceBreakdown(
    val baseCost: Double,
    val addonsCost: Double,
    val discounts: Double = 0.0,
    val lateFee: Double = 0.0,
    val damageFee: Double = 0.0,
    val taxes: Double = 0.0
) {
    fun total(): Double = baseCost + addonsCost - discounts + lateFee + damageFee + taxes
}

/** Result of rental operations */
sealed class RentalResult {
    data class Success(
        val rental: Rental,
        val priceBreakdown: PriceBreakdown
    ) : RentalResult()
    
    data class VehicleUnavailable(
        val vehicleId: String,
        val reason: String
    ) : RentalResult()
    
    data class InvalidPeriod(
        val reason: String
    ) : RentalResult()
    
    data class InvalidTransition(
        val from: RentalStatus,
        val to: RentalStatus
    ) : RentalResult()
    
    data class CustomerIneligible(
        val customerId: String,
        val reason: String
    ) : RentalResult()
    
    data class LateReturn(
        val rental: Rental,
        val hoursLate: Long,
        val lateFee: Double
    ) : RentalResult()
    
    data class DamageReported(
        val rental: Rental,
        val assessment: DamageAssessment
    ) : RentalResult()
    
    data object NotFound : RentalResult()
}

/** Seasonal pricing configuration */
data class SeasonalRate(
    val name: String,
    val startMonth: Int,
    val endMonth: Int,
    val multiplier: Double
) {
    fun appliesTo(dateTime: LocalDateTime): Boolean {
        val month = dateTime.monthValue
        return if (startMonth <= endMonth) {
            month in startMonth..endMonth
        } else {
            month >= startMonth || month <= endMonth
        }
    }
    
    companion object {
        val SUMMER = SeasonalRate("Summer Peak", 6, 8, 1.25)
        val WINTER_HOLIDAYS = SeasonalRate("Winter Holidays", 12, 1, 1.30)
        val SPRING_BREAK = SeasonalRate("Spring Break", 3, 4, 1.15)
        val OFF_PEAK = SeasonalRate("Off Peak", 9, 11, 0.90)
    }
}

/** Observer for rental events */
interface RentalObserver {
    fun onRentalCreated(rental: Rental)
    fun onVehiclePickedUp(rental: Rental)
    fun onVehicleReturned(rental: Rental)
    fun onLateReturn(rental: Rental, hoursLate: Long)
    fun onDamageReported(rental: Rental, assessment: DamageAssessment)
    fun onMaintenanceRequired(vehicle: Vehicle)
}

/** Vehicle configuration for factory creation */
data class VehicleConfig(
    val type: VehicleType,
    val make: String,
    val model: String,
    val year: Int,
    val dailyRate: Double,
    val fuelType: FuelType = FuelType.GASOLINE,
    val passengerCapacity: Int = 5,
    val features: Set<String> = emptySet()
)
