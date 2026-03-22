package com.systemdesign.hotelreservation.common

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Core domain models for Hotel Reservation System.
 * 
 * Extensibility Points:
 * - New room types: Add to RoomType enum
 * - New reservation statuses: Add to ReservationStatus enum and update state machine
 * - New amenities: No code changes needed (String-based)
 * - New guest preferences: Extend GuestPreferences data class
 * 
 * Breaking Changes Required For:
 * - Changing state machine transitions
 * - Adding multi-currency support
 */

enum class RoomType(val capacity: Int, val baseMultiplier: Double) {
    SINGLE(1, 1.0),
    DOUBLE(2, 1.5),
    SUITE(4, 3.0),
    PENTHOUSE(6, 5.0)
}

enum class ReservationStatus {
    PENDING,
    CONFIRMED,
    CHECKED_IN,
    CHECKED_OUT,
    CANCELLED,
    NO_SHOW
}

data class Room(
    val number: String,
    val type: RoomType,
    val floor: Int,
    val pricePerNight: Double,
    val amenities: Set<String> = emptySet(),
    val isAvailable: Boolean = true,
    val maxOccupancy: Int = type.capacity
) {
    fun hasAmenity(amenity: String): Boolean = amenities.contains(amenity.lowercase())
    
    fun matchesPreferences(preferences: GuestPreferences): Int {
        var score = 0
        if (preferences.preferredFloor != null && floor == preferences.preferredFloor) score += 10
        if (preferences.preferredRoomType != null && type == preferences.preferredRoomType) score += 20
        score += preferences.requiredAmenities.count { hasAmenity(it) } * 5
        if (preferences.minFloor != null && floor >= preferences.minFloor) score += 3
        if (preferences.maxFloor != null && floor <= preferences.maxFloor) score += 3
        return score
    }
}

data class Guest(
    val id: String,
    val name: String,
    val email: String,
    val phone: String,
    val loyaltyTier: LoyaltyTier = LoyaltyTier.STANDARD
)

enum class LoyaltyTier(val discountPercent: Double) {
    STANDARD(0.0),
    SILVER(5.0),
    GOLD(10.0),
    PLATINUM(15.0)
}

data class DateRange(
    val checkIn: LocalDate,
    val checkOut: LocalDate
) {
    init {
        require(!checkOut.isBefore(checkIn)) { "Check-out date must be on or after check-in date" }
    }
    
    val nights: Long get() = ChronoUnit.DAYS.between(checkIn, checkOut)
    
    fun overlaps(other: DateRange): Boolean {
        return !checkOut.isBefore(other.checkIn) && !other.checkOut.isBefore(checkIn)
    }
    
    fun contains(date: LocalDate): Boolean {
        return !date.isBefore(checkIn) && date.isBefore(checkOut)
    }
}

data class Reservation(
    val id: String,
    val guest: Guest,
    val room: Room,
    val dates: DateRange,
    var status: ReservationStatus = ReservationStatus.PENDING,
    val createdAt: LocalDate = LocalDate.now(),
    val specialRequests: String = "",
    val numberOfGuests: Int = 1,
    var paymentConfirmed: Boolean = false,
    var actualCheckIn: LocalDate? = null,
    var actualCheckOut: LocalDate? = null
) {
    fun calculateTotalPrice(): Double {
        val basePrice = room.pricePerNight * dates.nights
        val discount = basePrice * (guest.loyaltyTier.discountPercent / 100)
        return basePrice - discount
    }
    
    fun isActive(): Boolean = status in setOf(
        ReservationStatus.PENDING,
        ReservationStatus.CONFIRMED,
        ReservationStatus.CHECKED_IN
    )
}

data class Hotel(
    val id: String,
    val name: String,
    val rooms: List<Room>,
    val address: String = "",
    val starRating: Int = 3
) {
    fun getRoomsByType(type: RoomType): List<Room> = rooms.filter { it.type == type }
    fun getRoomsByFloor(floor: Int): List<Room> = rooms.filter { it.floor == floor }
    fun getAvailableRooms(): List<Room> = rooms.filter { it.isAvailable }
}

data class GuestPreferences(
    val preferredRoomType: RoomType? = null,
    val preferredFloor: Int? = null,
    val minFloor: Int? = null,
    val maxFloor: Int? = null,
    val requiredAmenities: Set<String> = emptySet(),
    val smokingRoom: Boolean = false,
    val accessibleRoom: Boolean = false
)

data class ReservationRequest(
    val guest: Guest,
    val dates: DateRange,
    val roomType: RoomType,
    val numberOfGuests: Int = 1,
    val preferences: GuestPreferences = GuestPreferences(),
    val specialRequests: String = ""
)

sealed class ReservationResult {
    data class Success(val reservation: Reservation) : ReservationResult()
    data class NoAvailability(val requestedDates: DateRange, val roomType: RoomType) : ReservationResult()
    data class InvalidRequest(val reason: String) : ReservationResult()
    data class PaymentRequired(val amount: Double, val reservationId: String) : ReservationResult()
    data class Conflict(val conflictingReservationId: String) : ReservationResult()
}

sealed class StatusChangeResult {
    data class Success(val reservation: Reservation) : StatusChangeResult()
    data class InvalidTransition(val from: ReservationStatus, val to: ReservationStatus) : StatusChangeResult()
    data class ValidationFailed(val reason: String) : StatusChangeResult()
}

data class AvailabilityQuery(
    val dates: DateRange,
    val roomType: RoomType? = null,
    val minPrice: Double? = null,
    val maxPrice: Double? = null,
    val requiredAmenities: Set<String> = emptySet()
)

data class HotelStatistics(
    val totalRooms: Int,
    val occupiedRooms: Int,
    val availableRooms: Int,
    val occupancyRate: Double,
    val reservationsByStatus: Map<ReservationStatus, Int>,
    val revenueToday: Double
)
