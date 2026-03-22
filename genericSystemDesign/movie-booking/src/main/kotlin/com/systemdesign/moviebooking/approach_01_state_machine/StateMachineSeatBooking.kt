package com.systemdesign.moviebooking.approach_01_state_machine

import com.systemdesign.moviebooking.common.*
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Approach 1: State Machine Pattern for Seat Booking
 * 
 * Models each seat's lifecycle as an explicit finite state machine with well-defined
 * transitions. Ensures atomic state changes and prevents invalid states.
 * 
 * State Machine:
 *   AVAILABLE ──lock()──> LOCKED ──confirm()──> BOOKED
 *       ↑                   │                     │
 *       │                   │ timeout()/cancel()  │ cancel()
 *       │                   ↓                     ↓
 *       └───────────── CANCELLED ←────────────────┘
 *       
 * Pattern: State Machine with explicit transitions and guards
 * 
 * Trade-offs:
 * + Explicit states make behavior predictable and auditable
 * + Invalid transitions are prevented at runtime
 * + Concurrent access is handled with fine-grained locking
 * + Easy to add new states (e.g., MAINTENANCE, RESERVED_VIP)
 * - More verbose than implicit state handling
 * - Lock contention under high load
 * 
 * When to use:
 * - When seat state integrity is critical
 * - When you need audit trail of state changes
 * - When concurrent bookings must be handled safely
 * 
 * Extensibility:
 * - Add new state: Add to SeatState enum, update transition rules
 * - Add lock extensions: Modify extendLock() method
 * - Add VIP reservation: Add RESERVED_VIP state with special transitions
 */

/** Represents a seat state transition */
data class StateTransition(
    val fromState: SeatState,
    val toState: SeatState,
    val userId: String?,
    val timestamp: Long = System.currentTimeMillis(),
    val reason: String? = null
)

/** State machine for managing individual seat state */
class SeatStateMachine(
    val seat: Seat,
    initialState: SeatState = SeatState.AVAILABLE
) {
    @Volatile
    private var currentState: SeatState = initialState
    
    @Volatile
    private var lockedBy: String? = null
    
    @Volatile
    private var lockedAt: Long? = null
    
    private val transitionHistory = CopyOnWriteArrayList<StateTransition>()
    
    val state: SeatState get() = currentState
    val lockOwner: String? get() = lockedBy
    val lockTime: Long? get() = lockedAt
    
    private val validTransitions = mapOf(
        SeatState.AVAILABLE to setOf(SeatState.LOCKED),
        SeatState.LOCKED to setOf(SeatState.BOOKED, SeatState.CANCELLED, SeatState.AVAILABLE),
        SeatState.BOOKED to setOf(SeatState.CANCELLED),
        SeatState.CANCELLED to setOf(SeatState.AVAILABLE)
    )
    
    fun canTransition(toState: SeatState): Boolean {
        return validTransitions[currentState]?.contains(toState) == true
    }
    
    @Synchronized
    fun lock(userId: String): Boolean {
        if (!canTransition(SeatState.LOCKED)) return false
        
        recordTransition(SeatState.LOCKED, userId, "Seat locked for booking")
        currentState = SeatState.LOCKED
        lockedBy = userId
        lockedAt = System.currentTimeMillis()
        return true
    }
    
    @Synchronized
    fun confirm(userId: String): Boolean {
        if (currentState != SeatState.LOCKED) return false
        if (lockedBy != userId) return false
        if (!canTransition(SeatState.BOOKED)) return false
        
        recordTransition(SeatState.BOOKED, userId, "Booking confirmed")
        currentState = SeatState.BOOKED
        return true
    }
    
    @Synchronized
    fun cancel(userId: String?, reason: String = "Cancelled"): Boolean {
        if (!canTransition(SeatState.CANCELLED)) return false
        if (currentState == SeatState.LOCKED && userId != null && lockedBy != userId) return false
        
        recordTransition(SeatState.CANCELLED, userId, reason)
        currentState = SeatState.CANCELLED
        lockedBy = null
        lockedAt = null
        return true
    }
    
    @Synchronized
    fun release(reason: String = "Released"): Boolean {
        if (currentState != SeatState.LOCKED && currentState != SeatState.CANCELLED) return false
        
        recordTransition(SeatState.AVAILABLE, null, reason)
        currentState = SeatState.AVAILABLE
        lockedBy = null
        lockedAt = null
        return true
    }
    
    @Synchronized
    fun isLockExpired(timeoutMs: Long): Boolean {
        if (currentState != SeatState.LOCKED) return false
        val lockTime = lockedAt ?: return false
        return System.currentTimeMillis() - lockTime > timeoutMs
    }
    
    @Synchronized
    fun expireLock(): Boolean {
        if (currentState != SeatState.LOCKED) return false
        
        recordTransition(SeatState.AVAILABLE, lockedBy, "Lock expired")
        currentState = SeatState.AVAILABLE
        lockedBy = null
        lockedAt = null
        return true
    }
    
    fun getHistory(): List<StateTransition> = transitionHistory.toList()
    
    private fun recordTransition(toState: SeatState, userId: String?, reason: String?) {
        transitionHistory.add(StateTransition(currentState, toState, userId, reason = reason))
    }
    
    fun toShowSeat(): ShowSeat = ShowSeat(seat, currentState, lockedBy, lockedAt)
}

/** Manages seat states for a specific show */
class ShowSeatManager(
    val show: Show,
    private val lockTimeoutMs: Long = 10 * 60 * 1000 // 10 minutes
) {
    private val seatMachines = ConcurrentHashMap<String, SeatStateMachine>()
    private val lock = ReentrantReadWriteLock()
    private val observers = CopyOnWriteArrayList<BookingObserver>()
    
    init {
        show.screen.generateSeats().forEach { seat ->
            seatMachines[seat.id] = SeatStateMachine(seat)
        }
    }
    
    fun getAvailableSeats(): List<ShowSeat> = lock.read {
        seatMachines.values
            .filter { it.state == SeatState.AVAILABLE }
            .map { it.toShowSeat() }
    }
    
    fun getAllSeats(): List<ShowSeat> = lock.read {
        seatMachines.values.map { it.toShowSeat() }
    }
    
    fun getSeatState(seatId: String): ShowSeat? = lock.read {
        seatMachines[seatId]?.toShowSeat()
    }
    
    /**
     * Atomically lock multiple seats for a user.
     * All-or-nothing: either all seats are locked or none.
     */
    fun lockSeats(seatIds: List<String>, userId: String): LockResult = lock.write {
        val machines = seatIds.mapNotNull { seatMachines[it] }
        if (machines.size != seatIds.size) {
            return@write LockResult.InvalidSeats(seatIds - machines.map { it.seat.id }.toSet())
        }
        
        val unavailable = machines.filter { it.state != SeatState.AVAILABLE }
        if (unavailable.isNotEmpty()) {
            return@write LockResult.SeatsUnavailable(unavailable.map { it.seat })
        }
        
        val locked = mutableListOf<Seat>()
        try {
            machines.forEach { machine ->
                if (machine.lock(userId)) {
                    locked.add(machine.seat)
                } else {
                    rollbackLocks(locked, userId)
                    return@write LockResult.LockFailed(machine.seat)
                }
            }
            
            val expiresAt = LocalDateTime.now().plusSeconds(lockTimeoutMs / 1000)
            notifySeatsLocked(locked, userId)
            LockResult.Success(locked, expiresAt)
        } catch (e: Exception) {
            rollbackLocks(locked, userId)
            LockResult.Error(e.message ?: "Unknown error")
        }
    }
    
    private fun rollbackLocks(seats: List<Seat>, userId: String) {
        seats.forEach { seat ->
            seatMachines[seat.id]?.cancel(userId, "Rollback")
            seatMachines[seat.id]?.release("Rollback")
        }
    }
    
    /**
     * Confirm locked seats after successful payment
     */
    fun confirmSeats(seatIds: List<String>, userId: String): ConfirmResult = lock.write {
        val machines = seatIds.mapNotNull { seatMachines[it] }
        
        val notOwned = machines.filter { it.lockOwner != userId }
        if (notOwned.isNotEmpty()) {
            return@write ConfirmResult.NotLockedByUser(notOwned.map { it.seat })
        }
        
        val expired = machines.filter { it.isLockExpired(lockTimeoutMs) }
        if (expired.isNotEmpty()) {
            expired.forEach { it.expireLock() }
            return@write ConfirmResult.LockExpired(expired.map { it.seat })
        }
        
        machines.forEach { machine ->
            if (!machine.confirm(userId)) {
                return@write ConfirmResult.ConfirmFailed(machine.seat)
            }
        }
        
        ConfirmResult.Success(machines.map { it.seat })
    }
    
    /**
     * Cancel booked or locked seats
     */
    fun cancelSeats(seatIds: List<String>, userId: String?, reason: String): CancelResult = lock.write {
        val machines = seatIds.mapNotNull { seatMachines[it] }
        val cancelled = mutableListOf<Seat>()
        
        machines.forEach { machine ->
            if (machine.cancel(userId, reason)) {
                machine.release("Cancelled: $reason")
                cancelled.add(machine.seat)
            }
        }
        
        if (cancelled.isNotEmpty()) {
            notifySeatsReleased(cancelled)
        }
        
        CancelResult.Success(cancelled, seatIds.size - cancelled.size)
    }
    
    /**
     * Check and expire timed-out locks
     */
    fun expireTimedOutLocks(): List<Seat> = lock.write {
        val expired = seatMachines.values
            .filter { it.isLockExpired(lockTimeoutMs) }
            .mapNotNull { machine ->
                if (machine.expireLock()) machine.seat else null
            }
        
        if (expired.isNotEmpty()) {
            notifySeatsReleased(expired)
        }
        expired
    }
    
    fun addObserver(observer: BookingObserver) {
        observers.add(observer)
    }
    
    fun removeObserver(observer: BookingObserver) {
        observers.remove(observer)
    }
    
    private fun notifySeatsLocked(seats: List<Seat>, userId: String) {
        observers.forEach { it.onSeatsLocked(show.id, seats, userId) }
    }
    
    private fun notifySeatsReleased(seats: List<Seat>) {
        observers.forEach { it.onSeatsReleased(show.id, seats) }
    }
}

/** Result of seat locking operation */
sealed class LockResult {
    data class Success(val seats: List<Seat>, val expiresAt: LocalDateTime) : LockResult()
    data class SeatsUnavailable(val unavailable: List<Seat>) : LockResult()
    data class InvalidSeats(val invalidIds: List<String>) : LockResult()
    data class LockFailed(val seat: Seat) : LockResult()
    data class Error(val message: String) : LockResult()
}

/** Result of seat confirmation operation */
sealed class ConfirmResult {
    data class Success(val seats: List<Seat>) : ConfirmResult()
    data class NotLockedByUser(val seats: List<Seat>) : ConfirmResult()
    data class LockExpired(val seats: List<Seat>) : ConfirmResult()
    data class ConfirmFailed(val seat: Seat) : ConfirmResult()
}

/** Result of seat cancellation operation */
sealed class CancelResult {
    data class Success(val cancelled: List<Seat>, val failedCount: Int) : CancelResult()
}

/**
 * Main booking service using state machine approach
 */
class StateMachineBookingService(
    private val lockTimeoutMs: Long = 10 * 60 * 1000
) {
    private val showManagers = ConcurrentHashMap<String, ShowSeatManager>()
    private val bookings = ConcurrentHashMap<String, Booking>()
    private val observers = CopyOnWriteArrayList<BookingObserver>()
    
    fun registerShow(show: Show): ShowSeatManager {
        val manager = ShowSeatManager(show, lockTimeoutMs)
        observers.forEach { manager.addObserver(it) }
        showManagers[show.id] = manager
        return manager
    }
    
    fun getShowManager(showId: String): ShowSeatManager? = showManagers[showId]
    
    /**
     * Initiate a booking by locking seats
     */
    fun initiateBooking(
        showId: String,
        seatIds: List<String>,
        user: User
    ): BookingResult {
        val manager = showManagers[showId] 
            ?: return BookingResult.ShowNotAvailable(showId, "Show not found")
        
        if (manager.show.hasStarted()) {
            return BookingResult.ShowNotAvailable(showId, "Show has already started")
        }
        
        return when (val lockResult = manager.lockSeats(seatIds, user.id)) {
            is LockResult.Success -> {
                val booking = Booking(
                    show = manager.show,
                    seats = lockResult.seats,
                    user = user,
                    status = BookingStatus.PENDING,
                    totalPrice = calculatePrice(manager.show, lockResult.seats),
                    expiresAt = lockResult.expiresAt
                )
                bookings[booking.id] = booking
                BookingResult.Success(booking)
            }
            is LockResult.SeatsUnavailable -> 
                BookingResult.SeatUnavailable(lockResult.unavailable)
            is LockResult.InvalidSeats -> 
                BookingResult.ValidationError(listOf("Invalid seat IDs: ${lockResult.invalidIds}"))
            is LockResult.LockFailed -> 
                BookingResult.SeatUnavailable(listOf(lockResult.seat), "Failed to lock seat")
            is LockResult.Error -> 
                BookingResult.ValidationError(listOf(lockResult.message))
        }
    }
    
    /**
     * Confirm booking after payment
     */
    fun confirmBooking(bookingId: String, payment: Payment): BookingResult {
        val booking = bookings[bookingId] 
            ?: return BookingResult.ValidationError(listOf("Booking not found"))
        
        if (booking.status != BookingStatus.PENDING) {
            return BookingResult.ValidationError(listOf("Booking is not in pending state"))
        }
        
        if (booking.isExpired) {
            cancelBooking(bookingId, "Booking expired")
            return BookingResult.Timeout(booking)
        }
        
        val manager = showManagers[booking.show.id]
            ?: return BookingResult.ShowNotAvailable(booking.show.id, "Show not found")
        
        return when (val confirmResult = manager.confirmSeats(
            booking.seats.map { it.id }, 
            booking.user.id
        )) {
            is ConfirmResult.Success -> {
                val confirmedBooking = booking.copy(
                    status = BookingStatus.CONFIRMED,
                    confirmedAt = LocalDateTime.now()
                )
                bookings[bookingId] = confirmedBooking
                notifyBookingConfirmed(confirmedBooking)
                BookingResult.Success(confirmedBooking)
            }
            is ConfirmResult.LockExpired -> {
                bookings[bookingId] = booking.copy(status = BookingStatus.EXPIRED)
                BookingResult.Timeout(booking)
            }
            is ConfirmResult.NotLockedByUser -> 
                BookingResult.SeatUnavailable(confirmResult.seats, "Seats not locked by this user")
            is ConfirmResult.ConfirmFailed -> 
                BookingResult.SeatUnavailable(listOf(confirmResult.seat), "Failed to confirm seat")
        }
    }
    
    /**
     * Cancel a booking
     */
    fun cancelBooking(bookingId: String, reason: String): BookingResult {
        val booking = bookings[bookingId] 
            ?: return BookingResult.ValidationError(listOf("Booking not found"))
        
        if (booking.status.isFinal()) {
            return BookingResult.ValidationError(listOf("Cannot cancel a ${booking.status} booking"))
        }
        
        val manager = showManagers[booking.show.id]
        manager?.cancelSeats(booking.seats.map { it.id }, booking.user.id, reason)
        
        val cancelledBooking = booking.copy(status = BookingStatus.CANCELLED)
        bookings[bookingId] = cancelledBooking
        notifyBookingCancelled(cancelledBooking, reason)
        
        return BookingResult.Success(cancelledBooking)
    }
    
    fun getBooking(bookingId: String): Booking? = bookings[bookingId]
    
    fun getUserBookings(userId: String): List<Booking> {
        return bookings.values.filter { it.user.id == userId }
    }
    
    /**
     * Run expiration check for all shows
     */
    fun processExpirations() {
        showManagers.values.forEach { manager ->
            val expired = manager.expireTimedOutLocks()
            if (expired.isNotEmpty()) {
                bookings.values
                    .filter { it.status == BookingStatus.PENDING && it.show.id == manager.show.id }
                    .filter { booking -> booking.seats.any { seat -> expired.any { it.id == seat.id } } }
                    .forEach { booking ->
                        bookings[booking.id] = booking.copy(status = BookingStatus.EXPIRED)
                        notifyBookingExpired(booking)
                    }
            }
        }
    }
    
    fun addObserver(observer: BookingObserver) {
        observers.add(observer)
        showManagers.values.forEach { it.addObserver(observer) }
    }
    
    fun removeObserver(observer: BookingObserver) {
        observers.remove(observer)
        showManagers.values.forEach { it.removeObserver(observer) }
    }
    
    private fun calculatePrice(show: Show, seats: List<Seat>): Double {
        return seats.sumOf { show.basePrice * it.type.priceMultiplier }
    }
    
    private fun notifyBookingConfirmed(booking: Booking) {
        observers.forEach { it.onBookingConfirmed(booking) }
    }
    
    private fun notifyBookingCancelled(booking: Booking, reason: String) {
        observers.forEach { it.onBookingCancelled(booking, reason) }
    }
    
    private fun notifyBookingExpired(booking: Booking) {
        observers.forEach { it.onBookingExpired(booking) }
    }
}
