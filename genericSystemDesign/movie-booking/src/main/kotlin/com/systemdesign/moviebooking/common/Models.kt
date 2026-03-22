package com.systemdesign.moviebooking.common

import java.time.LocalDateTime
import java.util.UUID

/**
 * Core domain models for the Movie Booking System.
 * 
 * Extensibility Points:
 * - New seat types: Add to SeatType enum (e.g., RECLINER, COUPLE, BALCONY)
 * - New selection strategies: Implement SeatSelectionStrategy interface
 * - New payment methods: Add variants to Payment sealed class
 * - New booking statuses: Add to BookingStatus enum (e.g., REFUNDED, ON_HOLD)
 * - New notification channels: Implement BookingObserver interface
 * 
 * Breaking Changes Required For:
 * - Changing seat state machine transitions
 * - Adding seat dependencies (e.g., social distancing rules)
 * - Multi-show booking (current model is single-show)
 */

/** Types of seats with different pricing tiers */
enum class SeatType(val priceMultiplier: Double) {
    REGULAR(1.0),
    PREMIUM(1.5),      // Better viewing angle, more legroom
    VIP(2.5),          // Best location, complimentary services
    WHEELCHAIR(1.0);   // Accessible seating

    companion object {
        /** 
         * Extensibility: Add new seat types here.
         * Example: RECLINER(2.0), COUPLE(1.8), BALCONY(1.3)
         */
        fun fromString(value: String): SeatType = 
            entries.find { it.name.equals(value, ignoreCase = true) } ?: REGULAR
    }
}

/** State machine states for a seat in a specific show */
enum class SeatState {
    AVAILABLE,   // Can be selected
    LOCKED,      // Temporarily held during booking process
    BOOKED,      // Successfully booked and paid
    CANCELLED;   // Booking was cancelled, seat may be available again

    fun canTransitionTo(newState: SeatState): Boolean {
        return when (this) {
            AVAILABLE -> newState == LOCKED
            LOCKED -> newState in setOf(BOOKED, CANCELLED, AVAILABLE) // AVAILABLE for timeout
            BOOKED -> newState == CANCELLED
            CANCELLED -> newState == AVAILABLE
        }
    }
}

/** Represents a seat in the theater */
data class Seat(
    val id: String = UUID.randomUUID().toString(),
    val row: Char,
    val number: Int,
    val type: SeatType = SeatType.REGULAR
) {
    val label: String get() = "$row$number"
    
    override fun toString(): String = "$label (${type.name})"
}

/** Seat with its current state for a specific show */
data class ShowSeat(
    val seat: Seat,
    val state: SeatState = SeatState.AVAILABLE,
    val lockedBy: String? = null,
    val lockedAt: Long? = null
) {
    fun isAvailable(): Boolean = state == SeatState.AVAILABLE
    fun isLockedBy(userId: String): Boolean = state == SeatState.LOCKED && lockedBy == userId
}

/** Screen/auditorium in the theater */
data class Screen(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val rows: Int,
    val seatsPerRow: Int,
    val seatLayout: Map<String, SeatType> = emptyMap()
) {
    val totalSeats: Int get() = rows * seatsPerRow
    
    fun generateSeats(): List<Seat> {
        val seats = mutableListOf<Seat>()
        for (rowIndex in 0 until rows) {
            val rowChar = ('A' + rowIndex)
            for (seatNum in 1..seatsPerRow) {
                val seatId = "$rowChar$seatNum"
                val type = seatLayout[seatId] ?: SeatType.REGULAR
                seats.add(Seat(seatId, rowChar, seatNum, type))
            }
        }
        return seats
    }
}

/** Movie being shown */
data class Movie(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val durationMinutes: Int,
    val genre: Genre,
    val rating: String = "PG-13",
    val language: String = "English"
)

/** Movie genres - extensible */
enum class Genre {
    ACTION, COMEDY, DRAMA, HORROR, SCIFI, THRILLER, ROMANCE, ANIMATION, DOCUMENTARY
}

/** A specific showing of a movie */
data class Show(
    val id: String = UUID.randomUUID().toString(),
    val movie: Movie,
    val screen: Screen,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime = startTime.plusMinutes(movie.durationMinutes.toLong() + 30),
    val basePrice: Double = 10.0
) {
    fun isUpcoming(): Boolean = startTime.isAfter(LocalDateTime.now())
    fun hasStarted(): Boolean = LocalDateTime.now().isAfter(startTime)
    fun hasEnded(): Boolean = LocalDateTime.now().isAfter(endTime)
}

/** User making a booking */
data class User(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val email: String,
    val phone: String? = null
)

/** Booking status */
enum class BookingStatus {
    PENDING,     // Seats locked, awaiting payment
    CONFIRMED,   // Payment successful
    CANCELLED,   // User or system cancelled
    EXPIRED;     // Lock timeout without payment

    fun isFinal(): Boolean = this in setOf(CONFIRMED, CANCELLED, EXPIRED)
}

/** A booking record */
data class Booking(
    val id: String = UUID.randomUUID().toString(),
    val show: Show,
    val seats: List<Seat>,
    val user: User,
    val status: BookingStatus = BookingStatus.PENDING,
    val totalPrice: Double,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val confirmedAt: LocalDateTime? = null,
    val expiresAt: LocalDateTime = createdAt.plusMinutes(10)
) {
    val isExpired: Boolean 
        get() = status == BookingStatus.PENDING && LocalDateTime.now().isAfter(expiresAt)
    
    fun calculatePrice(): Double {
        return seats.sumOf { seat -> 
            show.basePrice * seat.type.priceMultiplier 
        }
    }
}

/** Payment methods - Strategy Pattern for payment processing */
sealed class Payment {
    abstract val amount: Double
    abstract val transactionId: String
    
    data class CreditCard(
        override val amount: Double,
        val cardLast4: String,
        val cardType: String,
        override val transactionId: String = UUID.randomUUID().toString()
    ) : Payment()
    
    data class DebitCard(
        override val amount: Double,
        val cardLast4: String,
        val bankName: String,
        override val transactionId: String = UUID.randomUUID().toString()
    ) : Payment()
    
    data class UPI(
        override val amount: Double,
        val upiId: String,
        override val transactionId: String = UUID.randomUUID().toString()
    ) : Payment()
    
    data class Wallet(
        override val amount: Double,
        val walletProvider: String,
        val walletId: String,
        override val transactionId: String = UUID.randomUUID().toString()
    ) : Payment()
    
    data class NetBanking(
        override val amount: Double,
        val bankCode: String,
        override val transactionId: String = UUID.randomUUID().toString()
    ) : Payment()

    /**
     * Extensibility: Add new payment methods here.
     * Example: PayLater, GiftCard, CryptoCurrency
     */
}

/** Result of a booking operation */
sealed class BookingResult {
    data class Success(
        val booking: Booking,
        val confirmationCode: String = generateConfirmationCode()
    ) : BookingResult() {
        companion object {
            private fun generateConfirmationCode(): String =
                "BK${System.currentTimeMillis() % 1000000}"
        }
    }
    
    data class SeatUnavailable(
        val unavailableSeats: List<Seat>,
        val reason: String = "Selected seats are no longer available"
    ) : BookingResult()
    
    data class PaymentFailed(
        val payment: Payment,
        val errorCode: String,
        val message: String
    ) : BookingResult()
    
    data class Timeout(
        val booking: Booking,
        val message: String = "Booking session expired"
    ) : BookingResult()
    
    data class ShowNotAvailable(
        val showId: String,
        val reason: String
    ) : BookingResult()
    
    data class ValidationError(
        val errors: List<String>
    ) : BookingResult()
}

/** Observer interface for booking events */
interface BookingObserver {
    fun onSeatsLocked(showId: String, seats: List<Seat>, userId: String)
    fun onBookingConfirmed(booking: Booking)
    fun onBookingCancelled(booking: Booking, reason: String)
    fun onBookingExpired(booking: Booking)
    fun onSeatsReleased(showId: String, seats: List<Seat>)
    fun onWaitlistNotification(userId: String, showId: String, availableSeats: Int)
}

/** Events that can occur in the booking system */
sealed class BookingEvent {
    data class SeatsLocked(
        val showId: String, 
        val seats: List<Seat>, 
        val userId: String,
        val expiresAt: LocalDateTime
    ) : BookingEvent()
    
    data class BookingConfirmed(
        val booking: Booking,
        val confirmationCode: String
    ) : BookingEvent()
    
    data class BookingCancelled(
        val booking: Booking,
        val reason: String
    ) : BookingEvent()
    
    data class LockExpired(
        val showId: String,
        val seats: List<Seat>,
        val userId: String
    ) : BookingEvent()
    
    data class WaitlistNotified(
        val showId: String,
        val userId: String,
        val availableSeats: Int
    ) : BookingEvent()
}

/** Seat selection strategy interface - Strategy Pattern */
interface SeatSelectionStrategy {
    fun selectSeats(
        availableSeats: List<ShowSeat>,
        count: Int,
        preferences: SeatPreferences = SeatPreferences()
    ): List<Seat>
}

/** User preferences for seat selection */
data class SeatPreferences(
    val preferredType: SeatType? = null,
    val preferredRows: List<Char> = emptyList(),
    val requireContiguous: Boolean = true,
    val accessibilityRequired: Boolean = false
)

/** Waitlist entry for a show */
data class WaitlistEntry(
    val id: String = UUID.randomUUID().toString(),
    val showId: String,
    val userId: String,
    val seatsRequested: Int,
    val preferences: SeatPreferences = SeatPreferences(),
    val createdAt: LocalDateTime = LocalDateTime.now()
)
