package com.systemdesign.hotelreservation.approach_03_observer_notifications

import com.systemdesign.hotelreservation.common.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Approach 3: Observer Pattern for Notifications
 * 
 * Decouples the reservation system from notification concerns.
 * Multiple observers can react to reservation events independently.
 * 
 * Pattern: Observer / Publish-Subscribe
 * 
 * Trade-offs:
 * + Loose coupling between reservation logic and notifications
 * + Easy to add new notification channels
 * + Observers can be added/removed at runtime
 * + Each observer is testable in isolation
 * - Potential for event ordering issues
 * - Debugging can be harder with many observers
 * - Memory leaks if observers not properly removed
 * 
 * When to use:
 * - When multiple components need to react to events
 * - When notification logic should be separate from business logic
 * - When new notification channels may be added
 * 
 * Extensibility:
 * - New notification channel: Implement ReservationObserver
 * - New event type: Add method to interface and notify in system
 * - Async notifications: Wrap observer calls in coroutines
 */

interface ReservationObserver {
    fun onReservationCreated(reservation: Reservation)
    fun onReservationConfirmed(reservation: Reservation)
    fun onStatusChanged(reservation: Reservation, oldStatus: ReservationStatus, newStatus: ReservationStatus)
    fun onCheckIn(reservation: Reservation)
    fun onCheckOut(reservation: Reservation)
    fun onCancellation(reservation: Reservation, reason: String)
    fun onNoShow(reservation: Reservation)
    fun onUpcomingReminder(reservation: Reservation, daysUntilCheckIn: Int)
}

abstract class BaseObserver : ReservationObserver {
    override fun onReservationCreated(reservation: Reservation) {}
    override fun onReservationConfirmed(reservation: Reservation) {}
    override fun onStatusChanged(reservation: Reservation, oldStatus: ReservationStatus, newStatus: ReservationStatus) {}
    override fun onCheckIn(reservation: Reservation) {}
    override fun onCheckOut(reservation: Reservation) {}
    override fun onCancellation(reservation: Reservation, reason: String) {}
    override fun onNoShow(reservation: Reservation) {}
    override fun onUpcomingReminder(reservation: Reservation, daysUntilCheckIn: Int) {}
}

data class NotificationRecord(
    val id: String = UUID.randomUUID().toString(),
    val type: String,
    val channel: String,
    val recipient: String,
    val message: String,
    val timestamp: LocalDateTime = LocalDateTime.now(),
    val reservationId: String
)

class EmailNotifier : BaseObserver() {
    private val _sentEmails = mutableListOf<NotificationRecord>()
    val sentEmails: List<NotificationRecord> get() = _sentEmails.toList()
    
    override fun onReservationCreated(reservation: Reservation) {
        sendEmail(
            reservation,
            "Reservation Confirmation Pending",
            """
            Dear ${reservation.guest.name},
            
            Your reservation request has been received.
            Room: ${reservation.room.number} (${reservation.room.type})
            Check-in: ${reservation.dates.checkIn}
            Check-out: ${reservation.dates.checkOut}
            
            Please complete payment to confirm your reservation.
            
            Total: $${String.format("%.2f", reservation.calculateTotalPrice())}
            """.trimIndent()
        )
    }
    
    override fun onReservationConfirmed(reservation: Reservation) {
        sendEmail(
            reservation,
            "Reservation Confirmed",
            """
            Dear ${reservation.guest.name},
            
            Your reservation has been confirmed!
            
            Confirmation Number: ${reservation.id.take(8).uppercase()}
            Room: ${reservation.room.number} (${reservation.room.type})
            Floor: ${reservation.room.floor}
            Check-in: ${reservation.dates.checkIn} (after 3:00 PM)
            Check-out: ${reservation.dates.checkOut} (before 11:00 AM)
            
            Amenities: ${reservation.room.amenities.joinToString(", ")}
            
            We look forward to welcoming you!
            """.trimIndent()
        )
    }
    
    override fun onCheckIn(reservation: Reservation) {
        sendEmail(
            reservation,
            "Welcome to Our Hotel",
            """
            Dear ${reservation.guest.name},
            
            Welcome! You have successfully checked in.
            
            Room: ${reservation.room.number}
            WiFi Password: GUEST${reservation.room.number}
            
            For any assistance, please dial 0 from your room phone.
            
            Enjoy your stay!
            """.trimIndent()
        )
    }
    
    override fun onCheckOut(reservation: Reservation) {
        sendEmail(
            reservation,
            "Thank You for Staying With Us",
            """
            Dear ${reservation.guest.name},
            
            Thank you for choosing our hotel!
            
            Total charged: $${String.format("%.2f", reservation.calculateTotalPrice())}
            
            We hope to see you again soon.
            Please consider leaving a review!
            """.trimIndent()
        )
    }
    
    override fun onCancellation(reservation: Reservation, reason: String) {
        sendEmail(
            reservation,
            "Reservation Cancelled",
            """
            Dear ${reservation.guest.name},
            
            Your reservation has been cancelled.
            
            Confirmation Number: ${reservation.id.take(8).uppercase()}
            Reason: $reason
            
            If this was a mistake, please contact us immediately.
            """.trimIndent()
        )
    }
    
    override fun onUpcomingReminder(reservation: Reservation, daysUntilCheckIn: Int) {
        val dayWord = if (daysUntilCheckIn == 1) "day" else "days"
        sendEmail(
            reservation,
            "Your Stay is Coming Up!",
            """
            Dear ${reservation.guest.name},
            
            Just a friendly reminder that your stay is in $daysUntilCheckIn $dayWord!
            
            Check-in: ${reservation.dates.checkIn} (after 3:00 PM)
            Room: ${reservation.room.number} (${reservation.room.type})
            
            See you soon!
            """.trimIndent()
        )
    }
    
    private fun sendEmail(reservation: Reservation, subject: String, body: String) {
        val record = NotificationRecord(
            type = subject,
            channel = "EMAIL",
            recipient = reservation.guest.email,
            message = body,
            reservationId = reservation.id
        )
        _sentEmails.add(record)
    }
}

class SMSNotifier : BaseObserver() {
    private val _sentMessages = mutableListOf<NotificationRecord>()
    val sentMessages: List<NotificationRecord> get() = _sentMessages.toList()
    
    override fun onReservationConfirmed(reservation: Reservation) {
        sendSMS(
            reservation,
            "CONFIRMED",
            "Reservation confirmed! Conf#: ${reservation.id.take(8).uppercase()}. " +
                "Check-in: ${reservation.dates.checkIn}. Room: ${reservation.room.number}"
        )
    }
    
    override fun onCheckIn(reservation: Reservation) {
        sendSMS(
            reservation,
            "CHECK_IN",
            "Welcome! Room ${reservation.room.number}. WiFi: GUEST${reservation.room.number}. " +
                "Dial 0 for assistance."
        )
    }
    
    override fun onCancellation(reservation: Reservation, reason: String) {
        sendSMS(
            reservation,
            "CANCELLED",
            "Reservation ${reservation.id.take(8).uppercase()} cancelled. Contact us if this was an error."
        )
    }
    
    override fun onUpcomingReminder(reservation: Reservation, daysUntilCheckIn: Int) {
        sendSMS(
            reservation,
            "REMINDER",
            "Reminder: Your hotel stay is in $daysUntilCheckIn day(s). " +
                "Check-in after 3PM on ${reservation.dates.checkIn}."
        )
    }
    
    private fun sendSMS(reservation: Reservation, type: String, message: String) {
        val record = NotificationRecord(
            type = type,
            channel = "SMS",
            recipient = reservation.guest.phone,
            message = message,
            reservationId = reservation.id
        )
        _sentMessages.add(record)
    }
}

class AnalyticsTracker : BaseObserver() {
    private val _events = mutableListOf<AnalyticsEvent>()
    val events: List<AnalyticsEvent> get() = _events.toList()
    
    data class AnalyticsEvent(
        val eventType: String,
        val reservationId: String,
        val guestId: String,
        val roomType: RoomType,
        val revenue: Double,
        val timestamp: LocalDateTime = LocalDateTime.now(),
        val metadata: Map<String, Any> = emptyMap()
    )
    
    override fun onReservationCreated(reservation: Reservation) {
        trackEvent("reservation_created", reservation, mapOf(
            "nights" to reservation.dates.nights,
            "lead_time_days" to java.time.temporal.ChronoUnit.DAYS.between(
                LocalDate.now(), reservation.dates.checkIn
            )
        ))
    }
    
    override fun onReservationConfirmed(reservation: Reservation) {
        trackEvent("reservation_confirmed", reservation, mapOf(
            "conversion" to true,
            "loyalty_tier" to reservation.guest.loyaltyTier.name
        ))
    }
    
    override fun onCheckIn(reservation: Reservation) {
        trackEvent("check_in", reservation, mapOf(
            "on_time" to (reservation.actualCheckIn == reservation.dates.checkIn)
        ))
    }
    
    override fun onCheckOut(reservation: Reservation) {
        val actualNights = if (reservation.actualCheckIn != null && reservation.actualCheckOut != null) {
            java.time.temporal.ChronoUnit.DAYS.between(
                reservation.actualCheckIn, reservation.actualCheckOut
            )
        } else reservation.dates.nights
        
        trackEvent("check_out", reservation, mapOf(
            "early_checkout" to (reservation.actualCheckOut?.isBefore(reservation.dates.checkOut) == true),
            "actual_nights" to actualNights
        ))
    }
    
    override fun onCancellation(reservation: Reservation, reason: String) {
        trackEvent("cancellation", reservation, mapOf(
            "reason" to reason,
            "days_before_checkin" to java.time.temporal.ChronoUnit.DAYS.between(
                LocalDate.now(), reservation.dates.checkIn
            )
        ))
    }
    
    override fun onNoShow(reservation: Reservation) {
        trackEvent("no_show", reservation, mapOf(
            "lost_revenue" to reservation.calculateTotalPrice()
        ))
    }
    
    private fun trackEvent(eventType: String, reservation: Reservation, metadata: Map<String, Any>) {
        _events.add(AnalyticsEvent(
            eventType = eventType,
            reservationId = reservation.id,
            guestId = reservation.guest.id,
            roomType = reservation.room.type,
            revenue = reservation.calculateTotalPrice(),
            metadata = metadata
        ))
    }
    
    fun getRevenueByRoomType(): Map<RoomType, Double> {
        return events
            .filter { it.eventType == "reservation_confirmed" }
            .groupBy { it.roomType }
            .mapValues { (_, events) -> events.sumOf { it.revenue } }
    }
    
    fun getCancellationRate(): Double {
        val total = events.count { it.eventType in setOf("reservation_confirmed", "cancellation") }
        if (total == 0) return 0.0
        val cancelled = events.count { it.eventType == "cancellation" }
        return (cancelled.toDouble() / total) * 100
    }
    
    fun getNoShowRate(): Double {
        val confirmed = events.count { it.eventType == "reservation_confirmed" }
        if (confirmed == 0) return 0.0
        val noShows = events.count { it.eventType == "no_show" }
        return (noShows.toDouble() / confirmed) * 100
    }
}

class HousekeepingNotifier : BaseObserver() {
    private val _tasks = mutableListOf<HousekeepingTask>()
    val tasks: List<HousekeepingTask> get() = _tasks.toList()
    
    data class HousekeepingTask(
        val id: String = UUID.randomUUID().toString(),
        val roomNumber: String,
        val taskType: TaskType,
        val priority: Priority,
        val scheduledFor: LocalDate,
        val notes: String = ""
    )
    
    enum class TaskType { PREPARE_ROOM, CLEAN_AFTER_CHECKOUT, TURN_DOWN_SERVICE, DEEP_CLEAN }
    enum class Priority { LOW, NORMAL, HIGH, URGENT }
    
    override fun onReservationConfirmed(reservation: Reservation) {
        _tasks.add(HousekeepingTask(
            roomNumber = reservation.room.number,
            taskType = TaskType.PREPARE_ROOM,
            priority = if (reservation.guest.loyaltyTier >= LoyaltyTier.GOLD) Priority.HIGH else Priority.NORMAL,
            scheduledFor = reservation.dates.checkIn,
            notes = "VIP: ${reservation.guest.loyaltyTier}. Special requests: ${reservation.specialRequests}"
        ))
    }
    
    override fun onCheckOut(reservation: Reservation) {
        _tasks.add(HousekeepingTask(
            roomNumber = reservation.room.number,
            taskType = TaskType.CLEAN_AFTER_CHECKOUT,
            priority = Priority.HIGH,
            scheduledFor = reservation.actualCheckOut ?: reservation.dates.checkOut,
            notes = "Stayed ${reservation.dates.nights} nights"
        ))
    }
    
    override fun onNoShow(reservation: Reservation) {
        _tasks.removeAll { it.roomNumber == reservation.room.number && it.taskType == TaskType.PREPARE_ROOM }
    }
    
    fun getTasksForDate(date: LocalDate): List<HousekeepingTask> {
        return tasks.filter { it.scheduledFor == date }
    }
    
    fun getPendingTasksByPriority(): Map<Priority, List<HousekeepingTask>> {
        return tasks.groupBy { it.priority }
    }
}

class ObserverBasedHotelReservation(
    private val hotel: Hotel
) {
    private val observers = CopyOnWriteArrayList<ReservationObserver>()
    private val reservations = ConcurrentHashMap<String, Reservation>()
    private val roomReservations = ConcurrentHashMap<String, MutableList<Reservation>>()
    
    fun addObserver(observer: ReservationObserver) {
        observers.add(observer)
    }
    
    fun removeObserver(observer: ReservationObserver) {
        observers.remove(observer)
    }
    
    fun getObserverCount(): Int = observers.size
    
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
            return ReservationResult.NoAvailability(dates, room.type)
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
        
        notifyObservers { it.onReservationCreated(reservation) }
        
        return ReservationResult.Success(reservation)
    }
    
    fun confirmReservation(reservationId: String): StatusChangeResult {
        val reservation = reservations[reservationId]
            ?: return StatusChangeResult.ValidationFailed("Reservation not found")
        
        if (reservation.status != ReservationStatus.PENDING) {
            return StatusChangeResult.InvalidTransition(reservation.status, ReservationStatus.CONFIRMED)
        }
        
        val oldStatus = reservation.status
        reservation.status = ReservationStatus.CONFIRMED
        reservation.paymentConfirmed = true
        
        notifyObservers { it.onStatusChanged(reservation, oldStatus, ReservationStatus.CONFIRMED) }
        notifyObservers { it.onReservationConfirmed(reservation) }
        
        return StatusChangeResult.Success(reservation)
    }
    
    fun checkIn(reservationId: String, date: LocalDate = LocalDate.now()): StatusChangeResult {
        val reservation = reservations[reservationId]
            ?: return StatusChangeResult.ValidationFailed("Reservation not found")
        
        if (reservation.status != ReservationStatus.CONFIRMED) {
            return StatusChangeResult.InvalidTransition(reservation.status, ReservationStatus.CHECKED_IN)
        }
        
        val oldStatus = reservation.status
        reservation.status = ReservationStatus.CHECKED_IN
        reservation.actualCheckIn = date
        
        notifyObservers { it.onStatusChanged(reservation, oldStatus, ReservationStatus.CHECKED_IN) }
        notifyObservers { it.onCheckIn(reservation) }
        
        return StatusChangeResult.Success(reservation)
    }
    
    fun checkOut(reservationId: String, date: LocalDate = LocalDate.now()): StatusChangeResult {
        val reservation = reservations[reservationId]
            ?: return StatusChangeResult.ValidationFailed("Reservation not found")
        
        if (reservation.status != ReservationStatus.CHECKED_IN) {
            return StatusChangeResult.InvalidTransition(reservation.status, ReservationStatus.CHECKED_OUT)
        }
        
        val oldStatus = reservation.status
        reservation.status = ReservationStatus.CHECKED_OUT
        reservation.actualCheckOut = date
        
        notifyObservers { it.onStatusChanged(reservation, oldStatus, ReservationStatus.CHECKED_OUT) }
        notifyObservers { it.onCheckOut(reservation) }
        
        return StatusChangeResult.Success(reservation)
    }
    
    fun cancelReservation(reservationId: String, reason: String = "Cancelled by guest"): StatusChangeResult {
        val reservation = reservations[reservationId]
            ?: return StatusChangeResult.ValidationFailed("Reservation not found")
        
        if (reservation.status in setOf(ReservationStatus.CHECKED_IN, ReservationStatus.CHECKED_OUT)) {
            return StatusChangeResult.InvalidTransition(reservation.status, ReservationStatus.CANCELLED)
        }
        
        val oldStatus = reservation.status
        reservation.status = ReservationStatus.CANCELLED
        
        notifyObservers { it.onStatusChanged(reservation, oldStatus, ReservationStatus.CANCELLED) }
        notifyObservers { it.onCancellation(reservation, reason) }
        
        return StatusChangeResult.Success(reservation)
    }
    
    fun markAsNoShow(reservationId: String): StatusChangeResult {
        val reservation = reservations[reservationId]
            ?: return StatusChangeResult.ValidationFailed("Reservation not found")
        
        if (reservation.status != ReservationStatus.CONFIRMED) {
            return StatusChangeResult.InvalidTransition(reservation.status, ReservationStatus.NO_SHOW)
        }
        
        val oldStatus = reservation.status
        reservation.status = ReservationStatus.NO_SHOW
        
        notifyObservers { it.onStatusChanged(reservation, oldStatus, ReservationStatus.NO_SHOW) }
        notifyObservers { it.onNoShow(reservation) }
        
        return StatusChangeResult.Success(reservation)
    }
    
    fun sendUpcomingReminders(targetDate: LocalDate = LocalDate.now()) {
        val upcomingDays = listOf(1, 3, 7)
        
        reservations.values
            .filter { it.status == ReservationStatus.CONFIRMED }
            .forEach { reservation ->
                val daysUntil = java.time.temporal.ChronoUnit.DAYS.between(
                    targetDate, reservation.dates.checkIn
                ).toInt()
                
                if (daysUntil in upcomingDays) {
                    notifyObservers { it.onUpcomingReminder(reservation, daysUntil) }
                }
            }
    }
    
    fun getReservation(id: String): Reservation? = reservations[id]
    
    fun isRoomAvailable(roomNumber: String, dates: DateRange): Boolean {
        val existingReservations = roomReservations[roomNumber] ?: return true
        
        return existingReservations
            .filter { it.isActive() }
            .none { it.dates.overlaps(dates) }
    }
    
    fun getAvailableRooms(dates: DateRange, roomType: RoomType? = null): List<Room> {
        return hotel.rooms
            .filter { roomType == null || it.type == roomType }
            .filter { room -> isRoomAvailable(room.number, dates) }
    }
    
    private fun notifyObservers(action: (ReservationObserver) -> Unit) {
        observers.forEach { observer ->
            try {
                action(observer)
            } catch (e: Exception) {
                // Log but don't fail - observers shouldn't break the system
            }
        }
    }
    
    fun getActiveReservations(): List<Reservation> {
        return reservations.values.filter { it.isActive() }
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
