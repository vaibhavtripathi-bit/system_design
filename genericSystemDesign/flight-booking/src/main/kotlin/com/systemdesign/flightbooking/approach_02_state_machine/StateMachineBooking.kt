package com.systemdesign.flightbooking.approach_02_state_machine

import com.systemdesign.flightbooking.common.*
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Approach 2: State Machine for Booking Lifecycle
 * 
 * The booking system is modeled as an explicit finite state machine.
 * Each state has specific valid operations and transitions.
 * 
 * Pattern: State Machine
 * 
 * Trade-offs:
 * + Clear state transitions prevent invalid operations
 * + Easy to reason about booking lifecycle
 * + State-specific validation and rules
 * + Audit trail of state changes
 * - More boilerplate for state management
 * - State explosion if too many orthogonal concerns
 * 
 * When to use:
 * - When system has clear discrete states
 * - When operations are only valid in certain states
 * - When compliance requires state tracking
 * 
 * Extensibility:
 * - New state: Add to enum and update transition table
 * - New operation: Add method with state checks
 */

/** State transition definition */
data class StateTransition(
    val from: BookingStatus,
    val to: BookingStatus,
    val trigger: String,
    val condition: ((BookingContext) -> Boolean)? = null
)

/** Context for state transition conditions */
data class BookingContext(
    val booking: Booking,
    val paymentReceived: Boolean = false,
    val seatHolds: List<SeatHold> = emptyList(),
    val currentTime: LocalDateTime = LocalDateTime.now()
)

/** Result of state transition attempt */
sealed class TransitionResult {
    data class Success(
        val booking: Booking,
        val previousStatus: BookingStatus,
        val newStatus: BookingStatus
    ) : TransitionResult()
    
    data class InvalidTransition(
        val currentStatus: BookingStatus,
        val attemptedStatus: BookingStatus,
        val reason: String
    ) : TransitionResult()
    
    data class ConditionNotMet(
        val condition: String,
        val currentStatus: BookingStatus
    ) : TransitionResult()
}

/** Refund policy for cancellations */
interface RefundPolicy {
    fun calculateRefund(
        booking: Booking,
        cancellationTime: LocalDateTime,
        reason: CancellationReason
    ): RefundResult
}

/** Default refund policy based on time before departure */
class TimeBasedRefundPolicy : RefundPolicy {
    override fun calculateRefund(
        booking: Booking,
        cancellationTime: LocalDateTime,
        reason: CancellationReason
    ): RefundResult {
        val originalAmount = booking.totalPrice
        val firstFlight = booking.flights.firstOrNull()
        
        if (firstFlight == null) {
            return RefundResult(originalAmount, originalAmount, 0.0, reason)
        }
        
        val hoursBeforeDeparture = Duration.between(cancellationTime, firstFlight.departure).toHours()
        
        val (refundPercentage, penalty) = when {
            reason == CancellationReason.FLIGHT_CANCELLED -> 1.0 to 0.0
            reason == CancellationReason.SCHEDULE_CHANGE -> 1.0 to 0.0
            hoursBeforeDeparture >= 72 -> 0.90 to originalAmount * 0.10
            hoursBeforeDeparture >= 24 -> 0.50 to originalAmount * 0.50
            hoursBeforeDeparture >= 4 -> 0.25 to originalAmount * 0.75
            else -> 0.0 to originalAmount
        }
        
        val refundAmount = originalAmount * refundPercentage
        return RefundResult(originalAmount, refundAmount, penalty, reason)
    }
}

/** State machine for booking lifecycle */
class BookingStateMachine(
    private val refundPolicy: RefundPolicy = TimeBasedRefundPolicy(),
    private val paymentTimeoutMinutes: Long = 15,
    private val seatHoldMinutes: Long = 10
) {
    private val bookings = mutableMapOf<String, Booking>()
    private val seatHolds = mutableMapOf<String, MutableList<SeatHold>>()
    private val paymentDeadlines = mutableMapOf<String, LocalDateTime>()
    private val stateHistory = mutableMapOf<String, MutableList<Pair<BookingStatus, LocalDateTime>>>()
    private val observers = CopyOnWriteArrayList<BookingObserver>()
    
    private val validTransitions: Map<BookingStatus, Set<BookingStatus>> = mapOf(
        BookingStatus.SEARCHING to setOf(
            BookingStatus.SELECTED,
            BookingStatus.CANCELLED
        ),
        BookingStatus.SELECTED to setOf(
            BookingStatus.PAYMENT_PENDING,
            BookingStatus.SEARCHING,
            BookingStatus.CANCELLED
        ),
        BookingStatus.PAYMENT_PENDING to setOf(
            BookingStatus.CONFIRMED,
            BookingStatus.SELECTED,
            BookingStatus.CANCELLED
        ),
        BookingStatus.CONFIRMED to setOf(
            BookingStatus.CHECKED_IN,
            BookingStatus.CANCELLED
        ),
        BookingStatus.CHECKED_IN to setOf(
            BookingStatus.BOARDED,
            BookingStatus.CANCELLED
        ),
        BookingStatus.BOARDED to setOf(
            BookingStatus.COMPLETED
        ),
        BookingStatus.COMPLETED to emptySet(),
        BookingStatus.CANCELLED to emptySet()
    )
    
    fun createBooking(
        id: String,
        passenger: Passenger,
        flights: List<Flight>,
        priceBreakdown: PriceBreakdown
    ): Booking {
        val booking = Booking(
            id = id,
            passenger = passenger,
            flights = flights,
            seats = emptyList(),
            status = BookingStatus.SEARCHING,
            priceBreakdown = priceBreakdown
        )
        
        bookings[id] = booking
        recordStateChange(id, BookingStatus.SEARCHING)
        notifyBookingCreated(booking)
        
        return booking
    }
    
    fun getBooking(id: String): Booking? = bookings[id]
    
    fun canTransition(bookingId: String, toStatus: BookingStatus): Boolean {
        val booking = bookings[bookingId] ?: return false
        return validTransitions[booking.status]?.contains(toStatus) == true
    }
    
    fun transition(bookingId: String, toStatus: BookingStatus): TransitionResult {
        val booking = bookings[bookingId]
            ?: return TransitionResult.InvalidTransition(
                BookingStatus.SEARCHING,
                toStatus,
                "Booking not found"
            )
        
        val currentStatus = booking.status
        
        if (!canTransition(bookingId, toStatus)) {
            return TransitionResult.InvalidTransition(
                currentStatus,
                toStatus,
                "Invalid transition from $currentStatus to $toStatus"
            )
        }
        
        booking.status = toStatus
        recordStateChange(bookingId, toStatus)
        notifyStatusChanged(booking, currentStatus, toStatus)
        
        if (toStatus == BookingStatus.PAYMENT_PENDING) {
            paymentDeadlines[bookingId] = LocalDateTime.now().plusMinutes(paymentTimeoutMinutes)
        }
        
        return TransitionResult.Success(booking, currentStatus, toStatus)
    }
    
    fun selectSeats(bookingId: String, seats: List<Seat>): TransitionResult {
        val booking = bookings[bookingId]
            ?: return TransitionResult.InvalidTransition(
                BookingStatus.SEARCHING,
                BookingStatus.SELECTED,
                "Booking not found"
            )
        
        if (booking.status != BookingStatus.SEARCHING && booking.status != BookingStatus.SELECTED) {
            return TransitionResult.InvalidTransition(
                booking.status,
                BookingStatus.SELECTED,
                "Can only select seats when searching or already selected"
            )
        }
        
        val holdList = mutableListOf<SeatHold>()
        val expiresAt = LocalDateTime.now().plusMinutes(seatHoldMinutes)
        
        for (seat in seats) {
            val hold = SeatHold(
                seatNumber = seat.number,
                flightNumber = booking.flights.firstOrNull()?.number ?: "",
                expiresAt = expiresAt
            )
            holdList.add(hold)
            notifySeatAssigned(booking, seat)
        }
        
        seatHolds[bookingId] = holdList.toMutableList()
        
        val updatedBooking = booking.copy(seats = seats)
        bookings[bookingId] = updatedBooking
        
        return transition(bookingId, BookingStatus.SELECTED)
    }
    
    fun proceedToPayment(bookingId: String): TransitionResult {
        val booking = bookings[bookingId]
            ?: return TransitionResult.InvalidTransition(
                BookingStatus.SEARCHING,
                BookingStatus.PAYMENT_PENDING,
                "Booking not found"
            )
        
        if (booking.status != BookingStatus.SELECTED) {
            return TransitionResult.ConditionNotMet(
                "Seats must be selected before payment",
                booking.status
            )
        }
        
        if (booking.seats.isEmpty()) {
            return TransitionResult.ConditionNotMet(
                "No seats selected",
                booking.status
            )
        }
        
        return transition(bookingId, BookingStatus.PAYMENT_PENDING)
    }
    
    fun confirmPayment(bookingId: String, amount: Double): TransitionResult {
        val booking = bookings[bookingId]
            ?: return TransitionResult.InvalidTransition(
                BookingStatus.SEARCHING,
                BookingStatus.CONFIRMED,
                "Booking not found"
            )
        
        if (booking.status != BookingStatus.PAYMENT_PENDING) {
            return TransitionResult.ConditionNotMet(
                "Payment can only be confirmed when pending",
                booking.status
            )
        }
        
        val deadline = paymentDeadlines[bookingId]
        if (deadline != null && LocalDateTime.now().isAfter(deadline)) {
            transition(bookingId, BookingStatus.CANCELLED)
            return TransitionResult.ConditionNotMet(
                "Payment deadline expired",
                booking.status
            )
        }
        
        if (amount < booking.totalPrice) {
            return TransitionResult.ConditionNotMet(
                "Insufficient payment amount: $amount < ${booking.totalPrice}",
                booking.status
            )
        }
        
        notifyPaymentReceived(booking, amount)
        seatHolds.remove(bookingId)
        paymentDeadlines.remove(bookingId)
        
        return transition(bookingId, BookingStatus.CONFIRMED)
    }
    
    fun checkIn(bookingId: String): TransitionResult {
        val booking = bookings[bookingId]
            ?: return TransitionResult.InvalidTransition(
                BookingStatus.SEARCHING,
                BookingStatus.CHECKED_IN,
                "Booking not found"
            )
        
        if (booking.status != BookingStatus.CONFIRMED) {
            return TransitionResult.ConditionNotMet(
                "Can only check in confirmed bookings",
                booking.status
            )
        }
        
        val firstFlight = booking.flights.firstOrNull()
        if (firstFlight != null) {
            val hoursUntilDeparture = Duration.between(
                LocalDateTime.now(),
                firstFlight.departure
            ).toHours()
            
            if (hoursUntilDeparture > 24) {
                return TransitionResult.ConditionNotMet(
                    "Check-in opens 24 hours before departure",
                    booking.status
                )
            }
            
            if (hoursUntilDeparture < 1) {
                return TransitionResult.ConditionNotMet(
                    "Check-in closed (less than 1 hour before departure)",
                    booking.status
                )
            }
        }
        
        return transition(bookingId, BookingStatus.CHECKED_IN)
    }
    
    fun board(bookingId: String): TransitionResult {
        val booking = bookings[bookingId]
            ?: return TransitionResult.InvalidTransition(
                BookingStatus.SEARCHING,
                BookingStatus.BOARDED,
                "Booking not found"
            )
        
        if (booking.status != BookingStatus.CHECKED_IN) {
            return TransitionResult.ConditionNotMet(
                "Can only board checked-in passengers",
                booking.status
            )
        }
        
        return transition(bookingId, BookingStatus.BOARDED)
    }
    
    fun complete(bookingId: String): TransitionResult {
        val booking = bookings[bookingId]
            ?: return TransitionResult.InvalidTransition(
                BookingStatus.SEARCHING,
                BookingStatus.COMPLETED,
                "Booking not found"
            )
        
        if (booking.status != BookingStatus.BOARDED) {
            return TransitionResult.ConditionNotMet(
                "Can only complete boarded bookings",
                booking.status
            )
        }
        
        return transition(bookingId, BookingStatus.COMPLETED)
    }
    
    fun cancel(
        bookingId: String,
        reason: CancellationReason = CancellationReason.PASSENGER_REQUEST
    ): Pair<TransitionResult, RefundResult?> {
        val booking = bookings[bookingId]
            ?: return TransitionResult.InvalidTransition(
                BookingStatus.SEARCHING,
                BookingStatus.CANCELLED,
                "Booking not found"
            ) to null
        
        if (booking.status == BookingStatus.COMPLETED) {
            return TransitionResult.InvalidTransition(
                booking.status,
                BookingStatus.CANCELLED,
                "Cannot cancel completed booking"
            ) to null
        }
        
        if (booking.status == BookingStatus.CANCELLED) {
            return TransitionResult.InvalidTransition(
                booking.status,
                BookingStatus.CANCELLED,
                "Booking already cancelled"
            ) to null
        }
        
        val refundResult = if (booking.status in listOf(
            BookingStatus.CONFIRMED,
            BookingStatus.CHECKED_IN
        )) {
            refundPolicy.calculateRefund(booking, LocalDateTime.now(), reason)
        } else {
            RefundResult(booking.totalPrice, booking.totalPrice, 0.0, reason)
        }
        
        seatHolds.remove(bookingId)
        paymentDeadlines.remove(bookingId)
        
        val transitionResult = transition(bookingId, BookingStatus.CANCELLED)
        
        if (transitionResult is TransitionResult.Success) {
            notifyBookingCancelled(transitionResult.booking, refundResult)
        }
        
        return transitionResult to refundResult
    }
    
    fun checkTimeouts(): List<String> {
        val timedOutBookings = mutableListOf<String>()
        val now = LocalDateTime.now()
        
        for ((bookingId, deadline) in paymentDeadlines.toMap()) {
            if (now.isAfter(deadline)) {
                cancel(bookingId, CancellationReason.PAYMENT_TIMEOUT)
                timedOutBookings.add(bookingId)
            }
        }
        
        for ((bookingId, holds) in seatHolds.toMap()) {
            if (holds.any { it.isExpired() }) {
                val booking = bookings[bookingId]
                if (booking?.status == BookingStatus.SELECTED) {
                    transition(bookingId, BookingStatus.SEARCHING)
                    seatHolds.remove(bookingId)
                }
            }
        }
        
        return timedOutBookings
    }
    
    fun getStateHistory(bookingId: String): List<Pair<BookingStatus, LocalDateTime>> =
        stateHistory[bookingId]?.toList() ?: emptyList()
    
    fun getSeatHolds(bookingId: String): List<SeatHold> =
        seatHolds[bookingId]?.toList() ?: emptyList()
    
    fun getPaymentDeadline(bookingId: String): LocalDateTime? =
        paymentDeadlines[bookingId]
    
    private fun recordStateChange(bookingId: String, status: BookingStatus) {
        stateHistory.getOrPut(bookingId) { mutableListOf() }
            .add(status to LocalDateTime.now())
    }
    
    fun addObserver(observer: BookingObserver) {
        observers.add(observer)
    }
    
    fun removeObserver(observer: BookingObserver) {
        observers.remove(observer)
    }
    
    private fun notifyBookingCreated(booking: Booking) {
        observers.forEach { it.onBookingCreated(booking) }
    }
    
    private fun notifyStatusChanged(
        booking: Booking,
        oldStatus: BookingStatus,
        newStatus: BookingStatus
    ) {
        observers.forEach { it.onStatusChanged(booking, oldStatus, newStatus) }
    }
    
    private fun notifyPaymentReceived(booking: Booking, amount: Double) {
        observers.forEach { it.onPaymentReceived(booking, amount) }
    }
    
    private fun notifySeatAssigned(booking: Booking, seat: Seat) {
        observers.forEach { it.onSeatAssigned(booking, seat) }
    }
    
    private fun notifyBookingCancelled(booking: Booking, refund: RefundResult) {
        observers.forEach { it.onBookingCancelled(booking, refund) }
    }
}

/** Booking manager with state machine */
class FlightBookingManager(
    private val stateMachine: BookingStateMachine = BookingStateMachine()
) {
    private var bookingCounter = 0
    
    fun searchAndCreateBooking(
        passenger: Passenger,
        flights: List<Flight>,
        priceBreakdown: PriceBreakdown
    ): Booking {
        val bookingId = "BK${++bookingCounter}"
        return stateMachine.createBooking(bookingId, passenger, flights, priceBreakdown)
    }
    
    fun selectSeats(bookingId: String, seats: List<Seat>): BookingResult {
        return when (val result = stateMachine.selectSeats(bookingId, seats)) {
            is TransitionResult.Success -> BookingResult.Success(result.booking)
            is TransitionResult.InvalidTransition -> BookingResult.InvalidTransition(
                result.currentStatus,
                result.attemptedStatus
            )
            is TransitionResult.ConditionNotMet -> BookingResult.Error(result.condition)
        }
    }
    
    fun proceedToPayment(bookingId: String): BookingResult {
        return when (val result = stateMachine.proceedToPayment(bookingId)) {
            is TransitionResult.Success -> BookingResult.Success(result.booking)
            is TransitionResult.InvalidTransition -> BookingResult.InvalidTransition(
                result.currentStatus,
                result.attemptedStatus
            )
            is TransitionResult.ConditionNotMet -> BookingResult.Error(result.condition)
        }
    }
    
    fun confirmPayment(bookingId: String, amount: Double): BookingResult {
        return when (val result = stateMachine.confirmPayment(bookingId, amount)) {
            is TransitionResult.Success -> BookingResult.Success(result.booking)
            is TransitionResult.InvalidTransition -> BookingResult.InvalidTransition(
                result.currentStatus,
                result.attemptedStatus
            )
            is TransitionResult.ConditionNotMet -> BookingResult.Error(result.condition)
        }
    }
    
    fun checkIn(bookingId: String): BookingResult {
        return when (val result = stateMachine.checkIn(bookingId)) {
            is TransitionResult.Success -> BookingResult.Success(result.booking)
            is TransitionResult.InvalidTransition -> BookingResult.InvalidTransition(
                result.currentStatus,
                result.attemptedStatus
            )
            is TransitionResult.ConditionNotMet -> BookingResult.Error(result.condition)
        }
    }
    
    fun cancelBooking(
        bookingId: String,
        reason: CancellationReason = CancellationReason.PASSENGER_REQUEST
    ): BookingResult {
        val (transitionResult, refundResult) = stateMachine.cancel(bookingId, reason)
        
        return when (transitionResult) {
            is TransitionResult.Success -> {
                refundResult?.let { BookingResult.Cancelled(it) }
                    ?: BookingResult.Success(transitionResult.booking)
            }
            is TransitionResult.InvalidTransition -> BookingResult.InvalidTransition(
                transitionResult.currentStatus,
                transitionResult.attemptedStatus
            )
            is TransitionResult.ConditionNotMet -> BookingResult.Error(transitionResult.condition)
        }
    }
    
    fun getBooking(bookingId: String): Booking? = stateMachine.getBooking(bookingId)
    
    fun getStateHistory(bookingId: String) = stateMachine.getStateHistory(bookingId)
    
    fun addObserver(observer: BookingObserver) = stateMachine.addObserver(observer)
}
