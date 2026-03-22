package com.systemdesign.moviebooking.approach_03_observer_waitlist

import com.systemdesign.moviebooking.common.*
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.PriorityBlockingQueue

/**
 * Approach 3: Observer Pattern for Waitlist Notifications
 * 
 * Implements a waitlist system where users can subscribe to seat availability
 * notifications. When seats become available (cancelled/expired), observers
 * are automatically notified.
 * 
 * Pattern: Observer Pattern with Priority Queue
 * 
 * Trade-offs:
 * + Decoupled notification system
 * + Users don't need to poll for availability
 * + Fair waitlist processing with priorities
 * + Easy to add notification channels (email, SMS, push)
 * - Memory usage for storing waitlist entries
 * - Notification delivery is eventual (not immediate reservation)
 * 
 * When to use:
 * - When shows are frequently sold out
 * - When you want to improve user experience for popular shows
 * - When you need to fairly distribute cancelled seats
 * 
 * Extensibility:
 * - Add notification channel: Implement NotificationChannel interface
 * - Add priority rules: Implement WaitlistPriorityCalculator
 * - Add filters: Extend WaitlistEntry with criteria
 */

/** Priority calculator for waitlist ordering */
interface WaitlistPriorityCalculator {
    fun calculatePriority(entry: WaitlistEntry): Int
}

/** Default FIFO priority - earlier entries have higher priority */
class FifoPriorityCalculator : WaitlistPriorityCalculator {
    override fun calculatePriority(entry: WaitlistEntry): Int {
        return (Long.MAX_VALUE - entry.createdAt.toEpochSecond(java.time.ZoneOffset.UTC)).toInt()
    }
}

/** VIP users get higher priority */
class VipPriorityCalculator(
    private val vipUserIds: Set<String>
) : WaitlistPriorityCalculator {
    override fun calculatePriority(entry: WaitlistEntry): Int {
        val basePriority = (Long.MAX_VALUE - entry.createdAt.toEpochSecond(java.time.ZoneOffset.UTC)).toInt()
        return if (entry.userId in vipUserIds) basePriority + 1_000_000 else basePriority
    }
}

/** Notification channel interface */
interface NotificationChannel {
    fun sendNotification(userId: String, notification: WaitlistNotification): Boolean
}

/** In-app notification */
data class WaitlistNotification(
    val showId: String,
    val showName: String,
    val availableSeats: Int,
    val expiresAt: LocalDateTime,
    val bookingLink: String
)

/** Console/Log notification channel for testing */
class ConsoleNotificationChannel : NotificationChannel {
    private val sentNotifications = CopyOnWriteArrayList<Pair<String, WaitlistNotification>>()
    
    override fun sendNotification(userId: String, notification: WaitlistNotification): Boolean {
        println("📧 Notification to $userId: ${notification.availableSeats} seats available for ${notification.showName}")
        sentNotifications.add(userId to notification)
        return true
    }
    
    fun getSentNotifications(): List<Pair<String, WaitlistNotification>> = sentNotifications.toList()
}

/** Email notification channel */
class EmailNotificationChannel(
    private val emailProvider: EmailProvider
) : NotificationChannel {
    
    override fun sendNotification(userId: String, notification: WaitlistNotification): Boolean {
        val email = emailProvider.getEmailForUser(userId) ?: return false
        return emailProvider.sendEmail(
            to = email,
            subject = "Seats Available for ${notification.showName}!",
            body = buildEmailBody(notification)
        )
    }
    
    private fun buildEmailBody(notification: WaitlistNotification): String {
        return """
            Good news! ${notification.availableSeats} seat(s) are now available.
            
            Show: ${notification.showName}
            
            Book now before they're gone!
            Link: ${notification.bookingLink}
            
            This offer expires at: ${notification.expiresAt}
        """.trimIndent()
    }
}

/** Email provider interface */
interface EmailProvider {
    fun getEmailForUser(userId: String): String?
    fun sendEmail(to: String, subject: String, body: String): Boolean
}

/** Prioritized waitlist entry wrapper */
data class PrioritizedWaitlistEntry(
    val entry: WaitlistEntry,
    val priority: Int
) : Comparable<PrioritizedWaitlistEntry> {
    override fun compareTo(other: PrioritizedWaitlistEntry): Int {
        return other.priority.compareTo(this.priority)
    }
}

/** Manages waitlist for a specific show */
class ShowWaitlist(
    val showId: String,
    private val priorityCalculator: WaitlistPriorityCalculator = FifoPriorityCalculator()
) {
    private val waitlist = PriorityBlockingQueue<PrioritizedWaitlistEntry>()
    private val userEntries = ConcurrentHashMap<String, WaitlistEntry>()
    
    fun addToWaitlist(entry: WaitlistEntry): Boolean {
        if (userEntries.containsKey(entry.userId)) {
            return false
        }
        
        val priority = priorityCalculator.calculatePriority(entry)
        val prioritized = PrioritizedWaitlistEntry(entry, priority)
        waitlist.add(prioritized)
        userEntries[entry.userId] = entry
        return true
    }
    
    fun removeFromWaitlist(userId: String): Boolean {
        val entry = userEntries.remove(userId) ?: return false
        return waitlist.removeIf { it.entry.id == entry.id }
    }
    
    fun getPosition(userId: String): Int? {
        val userEntry = userEntries[userId] ?: return null
        var position = 1
        for (entry in waitlist.toList().sorted()) {
            if (entry.entry.id == userEntry.id) return position
            position++
        }
        return null
    }
    
    fun getNextInLine(seatsAvailable: Int): List<WaitlistEntry> {
        val eligible = mutableListOf<WaitlistEntry>()
        val sorted = waitlist.toList().sorted()
        
        for (entry in sorted) {
            if (entry.entry.seatsRequested <= seatsAvailable) {
                eligible.add(entry.entry)
                if (eligible.size >= 5) break
            }
        }
        
        return eligible
    }
    
    fun getWaitlistSize(): Int = waitlist.size
    
    fun getEntry(userId: String): WaitlistEntry? = userEntries[userId]
    
    fun clearExpired(maxAge: java.time.Duration) {
        val cutoff = LocalDateTime.now().minus(maxAge)
        val expired = waitlist.filter { it.entry.createdAt.isBefore(cutoff) }
        expired.forEach { entry ->
            waitlist.remove(entry)
            userEntries.remove(entry.entry.userId)
        }
    }
}

/** Waitlist manager for all shows */
class WaitlistManager(
    private val priorityCalculator: WaitlistPriorityCalculator = FifoPriorityCalculator(),
    private val notificationChannels: MutableList<NotificationChannel> = mutableListOf()
) : BookingObserver {
    
    private val showWaitlists = ConcurrentHashMap<String, ShowWaitlist>()
    private val showDetails = ConcurrentHashMap<String, Show>()
    private val observers = CopyOnWriteArrayList<WaitlistObserver>()
    
    fun registerShow(show: Show) {
        showDetails[show.id] = show
        showWaitlists.computeIfAbsent(show.id) { 
            ShowWaitlist(show.id, priorityCalculator) 
        }
    }
    
    fun addNotificationChannel(channel: NotificationChannel) {
        notificationChannels.add(channel)
    }
    
    fun joinWaitlist(
        showId: String,
        userId: String,
        seatsRequested: Int,
        preferences: SeatPreferences = SeatPreferences()
    ): WaitlistResult {
        val waitlist = showWaitlists[showId]
            ?: return WaitlistResult.ShowNotFound(showId)
        
        val entry = WaitlistEntry(
            showId = showId,
            userId = userId,
            seatsRequested = seatsRequested,
            preferences = preferences
        )
        
        return if (waitlist.addToWaitlist(entry)) {
            val position = waitlist.getPosition(userId) ?: 0
            notifyObservers { it.onUserJoinedWaitlist(showId, userId, position) }
            WaitlistResult.Joined(entry, position)
        } else {
            WaitlistResult.AlreadyOnWaitlist(showId, userId)
        }
    }
    
    fun leaveWaitlist(showId: String, userId: String): Boolean {
        val waitlist = showWaitlists[showId] ?: return false
        val removed = waitlist.removeFromWaitlist(userId)
        if (removed) {
            notifyObservers { it.onUserLeftWaitlist(showId, userId) }
        }
        return removed
    }
    
    fun getWaitlistPosition(showId: String, userId: String): Int? {
        return showWaitlists[showId]?.getPosition(userId)
    }
    
    fun getWaitlistSize(showId: String): Int {
        return showWaitlists[showId]?.getWaitlistSize() ?: 0
    }
    
    override fun onSeatsLocked(showId: String, seats: List<Seat>, userId: String) {
        // No action needed
    }
    
    override fun onBookingConfirmed(booking: Booking) {
        // No action needed
    }
    
    override fun onBookingCancelled(booking: Booking, reason: String) {
        notifyWaitlistUsers(booking.show.id, booking.seats.size)
    }
    
    override fun onBookingExpired(booking: Booking) {
        notifyWaitlistUsers(booking.show.id, booking.seats.size)
    }
    
    override fun onSeatsReleased(showId: String, seats: List<Seat>) {
        notifyWaitlistUsers(showId, seats.size)
    }
    
    override fun onWaitlistNotification(userId: String, showId: String, availableSeats: Int) {
        // This is called when we send notifications
    }
    
    private fun notifyWaitlistUsers(showId: String, availableSeats: Int) {
        val waitlist = showWaitlists[showId] ?: return
        val show = showDetails[showId] ?: return
        
        val eligibleUsers = waitlist.getNextInLine(availableSeats)
        if (eligibleUsers.isEmpty()) return
        
        val notification = WaitlistNotification(
            showId = showId,
            showName = show.movie.title,
            availableSeats = availableSeats,
            expiresAt = LocalDateTime.now().plusMinutes(15),
            bookingLink = "/booking/$showId"
        )
        
        for (entry in eligibleUsers) {
            sendNotifications(entry.userId, notification)
            notifyObservers { it.onWaitlistNotified(showId, entry.userId, availableSeats) }
        }
    }
    
    private fun sendNotifications(userId: String, notification: WaitlistNotification) {
        for (channel in notificationChannels) {
            try {
                channel.sendNotification(userId, notification)
            } catch (e: Exception) {
                println("Failed to send notification via ${channel::class.simpleName}: ${e.message}")
            }
        }
    }
    
    fun addObserver(observer: WaitlistObserver) {
        observers.add(observer)
    }
    
    fun removeObserver(observer: WaitlistObserver) {
        observers.remove(observer)
    }
    
    private fun notifyObservers(action: (WaitlistObserver) -> Unit) {
        observers.forEach { action(it) }
    }
    
    fun cleanupExpiredEntries(maxAge: java.time.Duration = java.time.Duration.ofHours(24)) {
        showWaitlists.values.forEach { it.clearExpired(maxAge) }
    }
}

/** Result of waitlist operations */
sealed class WaitlistResult {
    data class Joined(val entry: WaitlistEntry, val position: Int) : WaitlistResult()
    data class AlreadyOnWaitlist(val showId: String, val userId: String) : WaitlistResult()
    data class ShowNotFound(val showId: String) : WaitlistResult()
}

/** Observer for waitlist events */
interface WaitlistObserver {
    fun onUserJoinedWaitlist(showId: String, userId: String, position: Int)
    fun onUserLeftWaitlist(showId: String, userId: String)
    fun onWaitlistNotified(showId: String, userId: String, availableSeats: Int)
}

/**
 * Complete booking service with waitlist integration
 */
class WaitlistBookingService {
    private val showSeats = ConcurrentHashMap<String, MutableList<ShowSeat>>()
    private val shows = ConcurrentHashMap<String, Show>()
    private val waitlistManager = WaitlistManager()
    private val bookingObservers = CopyOnWriteArrayList<BookingObserver>()
    
    init {
        addObserver(waitlistManager)
    }
    
    fun registerShow(show: Show) {
        shows[show.id] = show
        showSeats[show.id] = show.screen.generateSeats()
            .map { ShowSeat(it) }
            .toMutableList()
        waitlistManager.registerShow(show)
    }
    
    fun getWaitlistManager(): WaitlistManager = waitlistManager
    
    fun addNotificationChannel(channel: NotificationChannel) {
        waitlistManager.addNotificationChannel(channel)
    }
    
    fun getAvailableSeats(showId: String): List<ShowSeat> {
        return showSeats[showId]?.filter { it.isAvailable() } ?: emptyList()
    }
    
    /**
     * Try to book, or join waitlist if no seats available
     */
    @Synchronized
    fun bookOrWaitlist(
        showId: String,
        seatIds: List<String>,
        user: User
    ): BookOrWaitlistResult {
        val seats = showSeats[showId] ?: return BookOrWaitlistResult.ShowNotFound(showId)
        val available = seats.filter { it.isAvailable() && it.seat.id in seatIds }
        
        return if (available.size == seatIds.size) {
            // Lock the seats for this user
            seats.forEachIndexed { index, showSeat ->
                if (showSeat.seat.id in seatIds) {
                    showSeats[showId]!![index] = showSeat.copy(
                        state = SeatState.LOCKED,
                        lockedBy = user.id,
                        lockedAt = System.currentTimeMillis()
                    )
                }
            }
            BookOrWaitlistResult.BookingInitiated(seatIds)
        } else {
            val waitlistResult = waitlistManager.joinWaitlist(
                showId = showId,
                userId = user.id,
                seatsRequested = seatIds.size
            )
            when (waitlistResult) {
                is WaitlistResult.Joined -> BookOrWaitlistResult.AddedToWaitlist(
                    waitlistResult.position,
                    waitlistResult.entry
                )
                is WaitlistResult.AlreadyOnWaitlist -> BookOrWaitlistResult.AlreadyOnWaitlist
                is WaitlistResult.ShowNotFound -> BookOrWaitlistResult.ShowNotFound(showId)
            }
        }
    }
    
    /**
     * Simulate seat release (for testing waitlist notifications)
     */
    fun releaseSeats(showId: String, seatIds: List<String>) {
        val seats = showSeats[showId] ?: return
        val releasedSeats = mutableListOf<Seat>()
        
        seats.forEachIndexed { index, showSeat ->
            if (showSeat.seat.id in seatIds && !showSeat.isAvailable()) {
                showSeats[showId]!![index] = showSeat.copy(
                    state = SeatState.AVAILABLE,
                    lockedBy = null,
                    lockedAt = null
                )
                releasedSeats.add(showSeat.seat)
            }
        }
        
        if (releasedSeats.isNotEmpty()) {
            notifySeatsReleased(showId, releasedSeats)
        }
    }
    
    fun addObserver(observer: BookingObserver) {
        bookingObservers.add(observer)
    }
    
    private fun notifySeatsReleased(showId: String, seats: List<Seat>) {
        bookingObservers.forEach { it.onSeatsReleased(showId, seats) }
    }
}

/** Result of book or waitlist operation */
sealed class BookOrWaitlistResult {
    data class BookingInitiated(val seatIds: List<String>) : BookOrWaitlistResult()
    data class AddedToWaitlist(val position: Int, val entry: WaitlistEntry) : BookOrWaitlistResult()
    data object AlreadyOnWaitlist : BookOrWaitlistResult()
    data class ShowNotFound(val showId: String) : BookOrWaitlistResult()
}

/**
 * Batch notification sender for scheduled processing
 */
class BatchNotificationProcessor(
    private val waitlistManager: WaitlistManager,
    private val notificationChannels: List<NotificationChannel>
) {
    private val pendingNotifications = CopyOnWriteArrayList<PendingNotification>()
    
    data class PendingNotification(
        val userId: String,
        val notification: WaitlistNotification,
        val createdAt: LocalDateTime = LocalDateTime.now()
    )
    
    fun queueNotification(userId: String, notification: WaitlistNotification) {
        pendingNotifications.add(PendingNotification(userId, notification))
    }
    
    fun processBatch(batchSize: Int = 100): Int {
        val batch = pendingNotifications.take(batchSize)
        var sent = 0
        
        for (pending in batch) {
            for (channel in notificationChannels) {
                try {
                    if (channel.sendNotification(pending.userId, pending.notification)) {
                        sent++
                        break
                    }
                } catch (e: Exception) {
                    continue
                }
            }
            pendingNotifications.remove(pending)
        }
        
        return sent
    }
    
    fun getPendingCount(): Int = pendingNotifications.size
}
