package com.systemdesign.flightbooking.common

import java.time.Duration
import java.time.LocalDateTime

/**
 * Core domain models for Flight Booking System.
 * 
 * Extensibility Points:
 * - New seat selection strategies: Implement SeatSelectionStrategy interface
 * - New booking states: Add to BookingStatus enum and update state machine
 * - New price components: Add to PriceBreakdown
 * 
 * Breaking Changes Required For:
 * - Changing booking lifecycle states
 * - Multi-currency support (requires redesign)
 */

/** Seat class categories */
enum class SeatClass(val multiplier: Double) {
    ECONOMY(1.0),
    PREMIUM_ECONOMY(1.5),
    BUSINESS(3.0),
    FIRST(5.0)
}

/** Seat position types */
enum class SeatType {
    WINDOW,
    MIDDLE,
    AISLE
}

/** Booking lifecycle states */
enum class BookingStatus {
    SEARCHING,
    SELECTED,
    PAYMENT_PENDING,
    CONFIRMED,
    CHECKED_IN,
    BOARDED,
    COMPLETED,
    CANCELLED
}

/** Cancellation reason categories */
enum class CancellationReason {
    PASSENGER_REQUEST,
    PAYMENT_TIMEOUT,
    FLIGHT_CANCELLED,
    SCHEDULE_CHANGE,
    NO_SHOW
}

/** Airport information */
data class Airport(
    val code: String,
    val name: String,
    val city: String,
    val country: String = "",
    val timezone: String = "UTC"
) {
    override fun toString(): String = "$code ($city)"
}

/** Individual seat on a flight */
data class Seat(
    val number: String,
    val seatClass: SeatClass,
    val type: SeatType,
    val price: Double,
    val row: Int = number.filter { it.isDigit() }.toIntOrNull() ?: 0,
    val isEmergencyExit: Boolean = false,
    val hasExtraLegroom: Boolean = false
) {
    val column: Char get() = number.lastOrNull { it.isLetter() } ?: 'A'
    
    fun isAvailable(bookedSeats: Set<String>): Boolean = number !in bookedSeats
}

/** Flight information */
data class Flight(
    val number: String,
    val airline: String = "",
    val origin: Airport,
    val destination: Airport,
    val departure: LocalDateTime,
    val arrival: LocalDateTime,
    val seats: List<Seat>,
    val aircraft: String = ""
) {
    val duration: Duration get() = Duration.between(departure, arrival)
    
    fun getAvailableSeats(bookedSeats: Set<String> = emptySet()): List<Seat> =
        seats.filter { it.isAvailable(bookedSeats) }
    
    fun getSeatsByClass(seatClass: SeatClass): List<Seat> =
        seats.filter { it.seatClass == seatClass }
    
    fun getSeatsByType(type: SeatType): List<Seat> =
        seats.filter { it.type == type }
}

/** Passenger information */
data class Passenger(
    val id: String,
    val name: String,
    val passport: String,
    val email: String = "",
    val phone: String = "",
    val dateOfBirth: LocalDateTime? = null,
    val frequentFlyerNumber: String? = null
)

/** Seat preferences for selection */
data class SeatPreferences(
    val preferredType: SeatType? = null,
    val preferredClass: SeatClass = SeatClass.ECONOMY,
    val preferExtraLegroom: Boolean = false,
    val preferEmergencyExit: Boolean = false,
    val nearRow: Int? = null,
    val passengerCount: Int = 1,
    val keepTogether: Boolean = true
)

/** Flight leg in an itinerary */
data class FlightLeg(
    val flight: Flight,
    val seat: Seat?,
    val passenger: Passenger,
    val legOrder: Int
) {
    val isLayover: Boolean get() = legOrder > 0
}

/** Connection between flights */
data class Connection(
    val arrivalFlight: Flight,
    val departureFlight: Flight,
    val layoverDuration: Duration
) {
    val isValid: Boolean get() = layoverDuration.toMinutes() >= 45
    val isTight: Boolean get() = layoverDuration.toMinutes() < 90
}

/** Price breakdown for booking */
data class PriceBreakdown(
    val baseFare: Double,
    val taxes: Double,
    val fees: Double,
    val seatUpgrade: Double = 0.0,
    val insurance: Double = 0.0,
    val discount: Double = 0.0
) {
    val total: Double get() = baseFare + taxes + fees + seatUpgrade + insurance - discount
    
    operator fun plus(other: PriceBreakdown): PriceBreakdown = PriceBreakdown(
        baseFare = baseFare + other.baseFare,
        taxes = taxes + other.taxes,
        fees = fees + other.fees,
        seatUpgrade = seatUpgrade + other.seatUpgrade,
        insurance = insurance + other.insurance,
        discount = discount + other.discount
    )
}

/** Individual booking */
data class Booking(
    val id: String,
    val passenger: Passenger,
    val flights: List<Flight>,
    val seats: List<Seat>,
    var status: BookingStatus,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val priceBreakdown: PriceBreakdown = PriceBreakdown(0.0, 0.0, 0.0),
    val pnr: String = generatePnr()
) {
    val totalPrice: Double get() = priceBreakdown.total
    val isActive: Boolean get() = status !in setOf(BookingStatus.CANCELLED, BookingStatus.COMPLETED)
    
    companion object {
        private fun generatePnr(): String {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            return (1..6).map { chars.random() }.joinToString("")
        }
    }
}

/** Complete itinerary with multiple bookings */
data class Itinerary(
    val id: String,
    val bookings: List<Booking>,
    val connections: List<Connection> = emptyList()
) {
    val totalPrice: Double get() = bookings.sumOf { it.totalPrice }
    val passengers: List<Passenger> get() = bookings.map { it.passenger }.distinct()
    val allFlights: List<Flight> get() = bookings.flatMap { it.flights }
    
    val isRoundTrip: Boolean get() {
        if (bookings.isEmpty()) return false
        val flights = allFlights
        if (flights.size < 2) return false
        return flights.first().origin.code == flights.last().destination.code
    }
}

/** Seat hold for temporary reservation */
data class SeatHold(
    val seatNumber: String,
    val flightNumber: String,
    val heldAt: LocalDateTime = LocalDateTime.now(),
    val expiresAt: LocalDateTime = heldAt.plusMinutes(15)
) {
    fun isExpired(): Boolean = LocalDateTime.now().isAfter(expiresAt)
}

/** Refund calculation result */
data class RefundResult(
    val originalAmount: Double,
    val refundAmount: Double,
    val penalty: Double,
    val reason: CancellationReason
) {
    val refundPercentage: Double get() = 
        if (originalAmount > 0) (refundAmount / originalAmount) * 100 else 0.0
}

/** Booking operation results */
sealed class BookingResult {
    data class Success(
        val booking: Booking,
        val message: String = "Booking successful"
    ) : BookingResult()
    
    data class SeatUnavailable(
        val requestedSeats: List<String>,
        val availableAlternatives: List<Seat> = emptyList()
    ) : BookingResult()
    
    data class PaymentFailed(
        val reason: String,
        val retryable: Boolean = true
    ) : BookingResult()
    
    data class InvalidTransition(
        val currentStatus: BookingStatus,
        val attemptedStatus: BookingStatus
    ) : BookingResult()
    
    data class BookingNotFound(val bookingId: String) : BookingResult()
    
    data class Cancelled(
        val refundResult: RefundResult
    ) : BookingResult()
    
    data class Error(val message: String) : BookingResult()
}

/** Search criteria for flights */
data class FlightSearchCriteria(
    val origin: String,
    val destination: String,
    val departureDate: LocalDateTime,
    val returnDate: LocalDateTime? = null,
    val passengers: Int = 1,
    val preferredClass: SeatClass = SeatClass.ECONOMY,
    val directOnly: Boolean = false,
    val maxLayoverMinutes: Int = 240
)

/** Observer for booking events */
interface BookingObserver {
    fun onBookingCreated(booking: Booking)
    fun onStatusChanged(booking: Booking, oldStatus: BookingStatus, newStatus: BookingStatus)
    fun onPaymentReceived(booking: Booking, amount: Double)
    fun onSeatAssigned(booking: Booking, seat: Seat)
    fun onBookingCancelled(booking: Booking, refund: RefundResult)
    fun onError(booking: Booking?, message: String)
}
