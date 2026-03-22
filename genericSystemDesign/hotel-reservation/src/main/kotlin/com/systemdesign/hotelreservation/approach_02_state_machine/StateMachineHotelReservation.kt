package com.systemdesign.hotelreservation.approach_02_state_machine

import com.systemdesign.hotelreservation.common.*
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Approach 2: State Machine Pattern for Reservation Lifecycle
 * 
 * Models the reservation as an explicit finite state machine with defined
 * transitions and guards for each state change.
 * 
 * Pattern: State Machine
 * 
 * Trade-offs:
 * + Clear and explicit state transitions
 * + Guards prevent invalid operations
 * + Easy to audit state change history
 * + Self-documenting system behavior
 * - More boilerplate for transition management
 * - State explosion with complex orthogonal concerns
 * 
 * When to use:
 * - When entities have clear lifecycle states
 * - When state transitions have business rules
 * - When audit trail is important
 * - When invalid states must be prevented
 * 
 * Extensibility:
 * - New state: Add to enum and update transition table
 * - New transition guard: Add to guard functions
 * - New side effects: Add to transition handlers
 */

data class StateTransition(
    val from: ReservationStatus,
    val to: ReservationStatus,
    val guard: (ReservationContext) -> Boolean = { true },
    val action: (ReservationContext) -> Unit = {}
)

data class ReservationContext(
    val reservation: Reservation,
    val currentDate: LocalDate = LocalDate.now(),
    val paymentReceived: Boolean = false,
    val forceTransition: Boolean = false
)

data class TransitionResult(
    val success: Boolean,
    val newStatus: ReservationStatus?,
    val errors: List<String> = emptyList()
)

data class StateChangeEvent(
    val reservationId: String,
    val fromStatus: ReservationStatus,
    val toStatus: ReservationStatus,
    val timestamp: LocalDate,
    val reason: String = ""
)

class ReservationStateMachine {
    
    private val transitions: List<StateTransition> = listOf(
        StateTransition(
            from = ReservationStatus.PENDING,
            to = ReservationStatus.CONFIRMED,
            guard = { ctx -> ctx.paymentReceived || ctx.reservation.paymentConfirmed }
        ),
        StateTransition(
            from = ReservationStatus.PENDING,
            to = ReservationStatus.CANCELLED,
            guard = { true }
        ),
        StateTransition(
            from = ReservationStatus.CONFIRMED,
            to = ReservationStatus.CHECKED_IN,
            guard = { ctx ->
                val reservation = ctx.reservation
                !ctx.currentDate.isBefore(reservation.dates.checkIn) &&
                    ctx.currentDate.isBefore(reservation.dates.checkOut.plusDays(1))
            }
        ),
        StateTransition(
            from = ReservationStatus.CONFIRMED,
            to = ReservationStatus.CANCELLED,
            guard = { ctx ->
                val reservation = ctx.reservation
                ctx.currentDate.isBefore(reservation.dates.checkIn) || ctx.forceTransition
            }
        ),
        StateTransition(
            from = ReservationStatus.CONFIRMED,
            to = ReservationStatus.NO_SHOW,
            guard = { ctx ->
                val reservation = ctx.reservation
                ctx.currentDate.isAfter(reservation.dates.checkIn)
            }
        ),
        StateTransition(
            from = ReservationStatus.CHECKED_IN,
            to = ReservationStatus.CHECKED_OUT,
            guard = { true }
        ),
        StateTransition(
            from = ReservationStatus.NO_SHOW,
            to = ReservationStatus.CANCELLED,
            guard = { true }
        )
    )
    
    fun getValidTransitions(status: ReservationStatus): Set<ReservationStatus> {
        return transitions
            .filter { it.from == status }
            .map { it.to }
            .toSet()
    }
    
    fun canTransition(context: ReservationContext, toStatus: ReservationStatus): Boolean {
        val currentStatus = context.reservation.status
        
        return transitions.any { transition ->
            transition.from == currentStatus &&
                transition.to == toStatus &&
                transition.guard(context)
        }
    }
    
    fun transition(context: ReservationContext, toStatus: ReservationStatus): TransitionResult {
        val currentStatus = context.reservation.status
        
        val validTransition = transitions.find { transition ->
            transition.from == currentStatus && transition.to == toStatus
        }
        
        if (validTransition == null) {
            return TransitionResult(
                success = false,
                newStatus = null,
                errors = listOf("Invalid transition from $currentStatus to $toStatus")
            )
        }
        
        if (!validTransition.guard(context)) {
            return TransitionResult(
                success = false,
                newStatus = null,
                errors = listOf("Transition guard failed for $currentStatus -> $toStatus")
            )
        }
        
        validTransition.action(context)
        context.reservation.status = toStatus
        
        return TransitionResult(success = true, newStatus = toStatus)
    }
}

class StateMachineHotelReservation(
    private val hotel: Hotel
) {
    private val stateMachine = ReservationStateMachine()
    private val reservations = ConcurrentHashMap<String, Reservation>()
    private val roomReservations = ConcurrentHashMap<String, MutableList<Reservation>>()
    private val stateHistory = ConcurrentHashMap<String, MutableList<StateChangeEvent>>()
    
    fun createReservation(
        guest: Guest,
        roomNumber: String,
        dates: DateRange,
        numberOfGuests: Int = 1,
        specialRequests: String = ""
    ): ReservationResult {
        val room = hotel.rooms.find { it.number == roomNumber }
            ?: return ReservationResult.InvalidRequest("Room $roomNumber not found")
        
        if (!isRoomAvailable(roomNumber, dates)) {
            val conflicting = getConflictingReservation(roomNumber, dates)
            return if (conflicting != null) {
                ReservationResult.Conflict(conflicting.id)
            } else {
                ReservationResult.NoAvailability(dates, room.type)
            }
        }
        
        if (numberOfGuests > room.maxOccupancy) {
            return ReservationResult.InvalidRequest(
                "Room capacity (${room.maxOccupancy}) exceeded by guest count ($numberOfGuests)"
            )
        }
        
        val reservation = Reservation(
            id = UUID.randomUUID().toString(),
            guest = guest,
            room = room,
            dates = dates,
            status = ReservationStatus.PENDING,
            numberOfGuests = numberOfGuests,
            specialRequests = specialRequests
        )
        
        reservations[reservation.id] = reservation
        roomReservations.getOrPut(roomNumber) { mutableListOf() }.add(reservation)
        recordStateChange(reservation.id, ReservationStatus.PENDING, ReservationStatus.PENDING, "Created")
        
        return ReservationResult.Success(reservation)
    }
    
    fun confirmReservation(reservationId: String, paymentReceived: Boolean = true): StatusChangeResult {
        val reservation = reservations[reservationId]
            ?: return StatusChangeResult.ValidationFailed("Reservation not found")
        
        val context = ReservationContext(
            reservation = reservation,
            paymentReceived = paymentReceived
        )
        
        if (!paymentReceived && !reservation.paymentConfirmed) {
            return StatusChangeResult.ValidationFailed("Payment required for confirmation")
        }
        
        val result = stateMachine.transition(context, ReservationStatus.CONFIRMED)
        
        return if (result.success) {
            reservation.paymentConfirmed = true
            recordStateChange(
                reservationId,
                ReservationStatus.PENDING,
                ReservationStatus.CONFIRMED,
                "Payment confirmed"
            )
            StatusChangeResult.Success(reservation)
        } else {
            StatusChangeResult.InvalidTransition(reservation.status, ReservationStatus.CONFIRMED)
        }
    }
    
    fun checkIn(reservationId: String, actualCheckInDate: LocalDate = LocalDate.now()): StatusChangeResult {
        val reservation = reservations[reservationId]
            ?: return StatusChangeResult.ValidationFailed("Reservation not found")
        
        val context = ReservationContext(
            reservation = reservation,
            currentDate = actualCheckInDate
        )
        
        val result = stateMachine.transition(context, ReservationStatus.CHECKED_IN)
        
        return if (result.success) {
            reservation.actualCheckIn = actualCheckInDate
            recordStateChange(
                reservationId,
                ReservationStatus.CONFIRMED,
                ReservationStatus.CHECKED_IN,
                "Guest checked in"
            )
            StatusChangeResult.Success(reservation)
        } else {
            when {
                reservation.status != ReservationStatus.CONFIRMED ->
                    StatusChangeResult.InvalidTransition(reservation.status, ReservationStatus.CHECKED_IN)
                actualCheckInDate.isBefore(reservation.dates.checkIn) ->
                    StatusChangeResult.ValidationFailed("Cannot check in before reservation date")
                else ->
                    StatusChangeResult.ValidationFailed(result.errors.firstOrNull() ?: "Check-in failed")
            }
        }
    }
    
    fun checkOut(reservationId: String, actualCheckOutDate: LocalDate = LocalDate.now()): StatusChangeResult {
        val reservation = reservations[reservationId]
            ?: return StatusChangeResult.ValidationFailed("Reservation not found")
        
        val context = ReservationContext(
            reservation = reservation,
            currentDate = actualCheckOutDate
        )
        
        val isEarlyCheckout = actualCheckOutDate.isBefore(reservation.dates.checkOut)
        
        val result = stateMachine.transition(context, ReservationStatus.CHECKED_OUT)
        
        return if (result.success) {
            reservation.actualCheckOut = actualCheckOutDate
            val reason = if (isEarlyCheckout) "Early checkout" else "Guest checked out"
            recordStateChange(
                reservationId,
                ReservationStatus.CHECKED_IN,
                ReservationStatus.CHECKED_OUT,
                reason
            )
            StatusChangeResult.Success(reservation)
        } else {
            StatusChangeResult.InvalidTransition(reservation.status, ReservationStatus.CHECKED_OUT)
        }
    }
    
    fun cancelReservation(reservationId: String, force: Boolean = false): StatusChangeResult {
        val reservation = reservations[reservationId]
            ?: return StatusChangeResult.ValidationFailed("Reservation not found")
        
        val context = ReservationContext(
            reservation = reservation,
            forceTransition = force
        )
        
        val result = stateMachine.transition(context, ReservationStatus.CANCELLED)
        
        return if (result.success) {
            recordStateChange(
                reservationId,
                reservation.status,
                ReservationStatus.CANCELLED,
                if (force) "Force cancelled" else "Cancelled by request"
            )
            StatusChangeResult.Success(reservation)
        } else {
            StatusChangeResult.InvalidTransition(reservation.status, ReservationStatus.CANCELLED)
        }
    }
    
    fun markAsNoShow(reservationId: String, currentDate: LocalDate = LocalDate.now()): StatusChangeResult {
        val reservation = reservations[reservationId]
            ?: return StatusChangeResult.ValidationFailed("Reservation not found")
        
        val context = ReservationContext(
            reservation = reservation,
            currentDate = currentDate
        )
        
        val result = stateMachine.transition(context, ReservationStatus.NO_SHOW)
        
        return if (result.success) {
            recordStateChange(
                reservationId,
                ReservationStatus.CONFIRMED,
                ReservationStatus.NO_SHOW,
                "Guest did not arrive"
            )
            StatusChangeResult.Success(reservation)
        } else {
            when {
                reservation.status != ReservationStatus.CONFIRMED ->
                    StatusChangeResult.InvalidTransition(reservation.status, ReservationStatus.NO_SHOW)
                !currentDate.isAfter(reservation.dates.checkIn) ->
                    StatusChangeResult.ValidationFailed("Cannot mark as no-show before check-in date")
                else ->
                    StatusChangeResult.ValidationFailed(result.errors.firstOrNull() ?: "No-show marking failed")
            }
        }
    }
    
    fun processNoShows(asOfDate: LocalDate = LocalDate.now()): List<Reservation> {
        val noShows = reservations.values.filter { reservation ->
            reservation.status == ReservationStatus.CONFIRMED &&
                asOfDate.isAfter(reservation.dates.checkIn)
        }
        
        noShows.forEach { reservation ->
            markAsNoShow(reservation.id, asOfDate)
        }
        
        return noShows
    }
    
    fun getReservation(id: String): Reservation? = reservations[id]
    
    fun getReservationHistory(reservationId: String): List<StateChangeEvent> {
        return stateHistory[reservationId]?.toList() ?: emptyList()
    }
    
    fun getValidNextStates(reservationId: String): Set<ReservationStatus> {
        val reservation = reservations[reservationId] ?: return emptySet()
        return stateMachine.getValidTransitions(reservation.status)
    }
    
    fun isRoomAvailable(roomNumber: String, dates: DateRange): Boolean {
        val existingReservations = roomReservations[roomNumber] ?: return true
        
        return existingReservations
            .filter { it.isActive() }
            .none { it.dates.overlaps(dates) }
    }
    
    private fun getConflictingReservation(roomNumber: String, dates: DateRange): Reservation? {
        return roomReservations[roomNumber]
            ?.filter { it.isActive() }
            ?.find { it.dates.overlaps(dates) }
    }
    
    private fun recordStateChange(
        reservationId: String,
        from: ReservationStatus,
        to: ReservationStatus,
        reason: String
    ) {
        val event = StateChangeEvent(
            reservationId = reservationId,
            fromStatus = from,
            toStatus = to,
            timestamp = LocalDate.now(),
            reason = reason
        )
        stateHistory.getOrPut(reservationId) { mutableListOf() }.add(event)
    }
    
    fun getActiveReservations(): List<Reservation> {
        return reservations.values.filter { it.isActive() }
    }
    
    fun getReservationsByStatus(status: ReservationStatus): List<Reservation> {
        return reservations.values.filter { it.status == status }
    }
    
    fun getTodaysCheckIns(date: LocalDate = LocalDate.now()): List<Reservation> {
        return reservations.values.filter { 
            it.status == ReservationStatus.CONFIRMED && it.dates.checkIn == date 
        }
    }
    
    fun getTodaysCheckOuts(date: LocalDate = LocalDate.now()): List<Reservation> {
        return reservations.values.filter { 
            it.status == ReservationStatus.CHECKED_IN && it.dates.checkOut == date 
        }
    }
    
    fun calculateEarlyCheckoutRefund(reservation: Reservation): Double {
        val actualCheckOut = reservation.actualCheckOut ?: return 0.0
        val plannedCheckOut = reservation.dates.checkOut
        
        if (!actualCheckOut.isBefore(plannedCheckOut)) return 0.0
        
        val unusedNights = java.time.temporal.ChronoUnit.DAYS.between(actualCheckOut, plannedCheckOut)
        return reservation.room.pricePerNight * unusedNights * 0.5
    }
    
    fun getStatistics(): HotelStatistics {
        val today = LocalDate.now()
        val checkedIn = reservations.values.filter { it.status == ReservationStatus.CHECKED_IN }
        
        return HotelStatistics(
            totalRooms = hotel.rooms.size,
            occupiedRooms = checkedIn.size,
            availableRooms = hotel.rooms.size - checkedIn.size,
            occupancyRate = if (hotel.rooms.isNotEmpty()) 
                (checkedIn.size.toDouble() / hotel.rooms.size) * 100 else 0.0,
            reservationsByStatus = reservations.values.groupingBy { it.status }.eachCount(),
            revenueToday = checkedIn.sumOf { it.room.pricePerNight }
        )
    }
}
