package com.systemdesign.moviebooking

import com.systemdesign.moviebooking.common.*
import com.systemdesign.moviebooking.approach_01_state_machine.*
import com.systemdesign.moviebooking.approach_02_strategy_selection.*
import com.systemdesign.moviebooking.approach_03_observer_waitlist.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MovieBookingTest {
    
    private fun createTestMovie(): Movie = Movie(
        id = "movie-1",
        title = "Test Movie",
        durationMinutes = 120,
        genre = Genre.ACTION
    )
    
    private fun createTestScreen(): Screen = Screen(
        id = "screen-1",
        name = "Screen 1",
        rows = 10,
        seatsPerRow = 15,
        seatLayout = mapOf(
            "A1" to SeatType.WHEELCHAIR,
            "A2" to SeatType.WHEELCHAIR,
            "J7" to SeatType.VIP,
            "J8" to SeatType.VIP,
            "E7" to SeatType.PREMIUM,
            "E8" to SeatType.PREMIUM
        )
    )
    
    private fun createTestShow(): Show = Show(
        id = "show-1",
        movie = createTestMovie(),
        screen = createTestScreen(),
        startTime = LocalDateTime.now().plusHours(2),
        basePrice = 10.0
    )
    
    private fun createTestUser(id: String = "user-1"): User = User(
        id = id,
        name = "Test User",
        email = "test@example.com"
    )
    
    @Nested
    inner class SeatStateMachineTest {
        
        private lateinit var stateMachine: SeatStateMachine
        
        @BeforeEach
        fun setup() {
            val seat = Seat("A1", 'A', 1, SeatType.REGULAR)
            stateMachine = SeatStateMachine(seat)
        }
        
        @Test
        fun `initial state is AVAILABLE`() {
            assertEquals(SeatState.AVAILABLE, stateMachine.state)
            assertNull(stateMachine.lockOwner)
        }
        
        @Test
        fun `can lock available seat`() {
            val result = stateMachine.lock("user-1")
            
            assertTrue(result)
            assertEquals(SeatState.LOCKED, stateMachine.state)
            assertEquals("user-1", stateMachine.lockOwner)
            assertNotNull(stateMachine.lockTime)
        }
        
        @Test
        fun `cannot lock already locked seat`() {
            stateMachine.lock("user-1")
            
            val result = stateMachine.lock("user-2")
            
            assertFalse(result)
            assertEquals("user-1", stateMachine.lockOwner)
        }
        
        @Test
        fun `can confirm locked seat by lock owner`() {
            stateMachine.lock("user-1")
            
            val result = stateMachine.confirm("user-1")
            
            assertTrue(result)
            assertEquals(SeatState.BOOKED, stateMachine.state)
        }
        
        @Test
        fun `cannot confirm seat locked by different user`() {
            stateMachine.lock("user-1")
            
            val result = stateMachine.confirm("user-2")
            
            assertFalse(result)
            assertEquals(SeatState.LOCKED, stateMachine.state)
        }
        
        @Test
        fun `can cancel locked seat`() {
            stateMachine.lock("user-1")
            
            val result = stateMachine.cancel("user-1", "Changed mind")
            
            assertTrue(result)
            assertEquals(SeatState.CANCELLED, stateMachine.state)
        }
        
        @Test
        fun `can cancel booked seat`() {
            stateMachine.lock("user-1")
            stateMachine.confirm("user-1")
            
            val result = stateMachine.cancel("user-1", "Refund requested")
            
            assertTrue(result)
            assertEquals(SeatState.CANCELLED, stateMachine.state)
        }
        
        @Test
        fun `can release cancelled seat to available`() {
            stateMachine.lock("user-1")
            stateMachine.cancel("user-1")
            
            val result = stateMachine.release()
            
            assertTrue(result)
            assertEquals(SeatState.AVAILABLE, stateMachine.state)
        }
        
        @Test
        fun `cannot transition from AVAILABLE to BOOKED directly`() {
            assertFalse(stateMachine.canTransition(SeatState.BOOKED))
        }
        
        @Test
        fun `lock expiration detection works`() {
            stateMachine.lock("user-1")
            
            // With a long timeout, lock should not be expired
            assertFalse(stateMachine.isLockExpired(10000))
            
            // Wait a bit and check with short timeout
            Thread.sleep(10)
            assertTrue(stateMachine.isLockExpired(5))
        }
        
        @Test
        fun `transition history is recorded`() {
            stateMachine.lock("user-1")
            stateMachine.confirm("user-1")
            stateMachine.cancel("user-1", "Test")
            
            val history = stateMachine.getHistory()
            
            assertEquals(3, history.size)
            assertEquals(SeatState.LOCKED, history[0].toState)
            assertEquals(SeatState.BOOKED, history[1].toState)
            assertEquals(SeatState.CANCELLED, history[2].toState)
        }
    }
    
    @Nested
    inner class ShowSeatManagerTest {
        
        private lateinit var manager: ShowSeatManager
        private lateinit var show: Show
        
        @BeforeEach
        fun setup() {
            show = createTestShow()
            manager = ShowSeatManager(show, lockTimeoutMs = 1000)
        }
        
        @Test
        fun `initializes all seats as available`() {
            val available = manager.getAvailableSeats()
            
            assertEquals(show.screen.totalSeats, available.size)
            assertTrue(available.all { it.isAvailable() })
        }
        
        @Test
        fun `can lock multiple seats atomically`() {
            val seatIds = listOf("A1", "A2", "A3")
            
            val result = manager.lockSeats(seatIds, "user-1")
            
            assertTrue(result is LockResult.Success)
            val success = result as LockResult.Success
            assertEquals(3, success.seats.size)
            
            val available = manager.getAvailableSeats()
            assertEquals(show.screen.totalSeats - 3, available.size)
        }
        
        @Test
        fun `lock fails if any seat is unavailable`() {
            manager.lockSeats(listOf("A1"), "user-1")
            
            val result = manager.lockSeats(listOf("A1", "A2"), "user-2")
            
            assertTrue(result is LockResult.SeatsUnavailable)
            
            val a2State = manager.getSeatState("A2")
            assertEquals(SeatState.AVAILABLE, a2State?.state)
        }
        
        @Test
        fun `confirm seats after locking`() {
            manager.lockSeats(listOf("A1", "A2"), "user-1")
            
            val result = manager.confirmSeats(listOf("A1", "A2"), "user-1")
            
            assertTrue(result is ConfirmResult.Success)
        }
        
        @Test
        fun `cancel seats releases them`() {
            manager.lockSeats(listOf("A1", "A2"), "user-1")
            
            val result = manager.cancelSeats(listOf("A1", "A2"), "user-1", "Test")
            
            assertTrue(result is CancelResult.Success)
            
            assertEquals(SeatState.AVAILABLE, manager.getSeatState("A1")?.state)
            assertEquals(SeatState.AVAILABLE, manager.getSeatState("A2")?.state)
        }
        
        @Test
        fun `expired locks are released`() {
            manager.lockSeats(listOf("A1"), "user-1")
            
            Thread.sleep(1100)
            
            val expired = manager.expireTimedOutLocks()
            
            assertEquals(1, expired.size)
            assertEquals("A1", expired[0].id)
            assertEquals(SeatState.AVAILABLE, manager.getSeatState("A1")?.state)
        }
    }
    
    @Nested
    inner class ConcurrentBookingTest {
        
        @Test
        fun `concurrent seat locking handles race conditions`() {
            val show = createTestShow()
            val manager = ShowSeatManager(show)
            val executor = Executors.newFixedThreadPool(10)
            val successCount = AtomicInteger(0)
            val failCount = AtomicInteger(0)
            val latch = CountDownLatch(10)
            
            repeat(10) { i ->
                executor.submit {
                    try {
                        val result = manager.lockSeats(listOf("A1"), "user-$i")
                        if (result is LockResult.Success) {
                            successCount.incrementAndGet()
                        } else {
                            failCount.incrementAndGet()
                        }
                    } finally {
                        latch.countDown()
                    }
                }
            }
            
            latch.await(5, TimeUnit.SECONDS)
            executor.shutdown()
            
            assertEquals(1, successCount.get())
            assertEquals(9, failCount.get())
        }
        
        @Test
        fun `multiple users can book different seats concurrently`() {
            val show = createTestShow()
            val manager = ShowSeatManager(show)
            val executor = Executors.newFixedThreadPool(5)
            val successCount = AtomicInteger(0)
            val latch = CountDownLatch(5)
            
            repeat(5) { i ->
                executor.submit {
                    try {
                        val seatId = "A${i + 1}"
                        val result = manager.lockSeats(listOf(seatId), "user-$i")
                        if (result is LockResult.Success) {
                            successCount.incrementAndGet()
                        }
                    } finally {
                        latch.countDown()
                    }
                }
            }
            
            latch.await(5, TimeUnit.SECONDS)
            executor.shutdown()
            
            assertEquals(5, successCount.get())
        }
    }
    
    @Nested
    inner class SeatSelectionStrategyTest {
        
        private lateinit var availableSeats: List<ShowSeat>
        
        @BeforeEach
        fun setup() {
            val screen = createTestScreen()
            availableSeats = screen.generateSeats().map { ShowSeat(it) }
        }
        
        @Test
        fun `BestAvailableStrategy selects contiguous seats`() {
            val strategy = BestAvailableStrategy()
            
            val selected = strategy.selectSeats(availableSeats, 3)
            
            assertEquals(3, selected.size)
            val sorted = selected.sortedBy { it.number }
            assertTrue(sorted[1].number == sorted[0].number + 1)
            assertTrue(sorted[2].number == sorted[1].number + 1)
        }
        
        @Test
        fun `BestAvailableStrategy prefers middle rows`() {
            val strategy = BestAvailableStrategy(preferMiddleRows = true)
            
            val selected = strategy.selectSeats(availableSeats, 2)
            
            assertTrue(selected.all { it.row in 'D'..'G' })
        }
        
        @Test
        fun `BestAvailableStrategy returns empty if not enough seats`() {
            val limited = availableSeats.take(2)
            val strategy = BestAvailableStrategy()
            
            val selected = strategy.selectSeats(limited, 5)
            
            assertTrue(selected.isEmpty())
        }
        
        @Test
        fun `ManualSelectionStrategy validates specific seats`() {
            val strategy = ManualSelectionStrategy()
            
            val result = strategy.selectSpecificSeats(
                availableSeats, 
                listOf("A1", "A2", "A3")
            )
            
            assertTrue(result is SelectionResult.Success)
            assertEquals(3, (result as SelectionResult.Success).seats.size)
        }
        
        @Test
        fun `ManualSelectionStrategy reports unavailable seats`() {
            val partiallyBooked = availableSeats.map { showSeat ->
                if (showSeat.seat.id == "A2") {
                    showSeat.copy(state = SeatState.BOOKED)
                } else {
                    showSeat
                }
            }
            val strategy = ManualSelectionStrategy()
            
            val result = strategy.selectSpecificSeats(
                partiallyBooked,
                listOf("A1", "A2", "A3")
            )
            
            assertTrue(result is SelectionResult.PartiallyUnavailable)
            val partial = result as SelectionResult.PartiallyUnavailable
            assertEquals(2, partial.available.size)
            assertEquals(listOf("A2"), partial.unavailable)
        }
        
        @Test
        fun `AccessibleFirstStrategy prioritizes wheelchair seats`() {
            val strategy = AccessibleFirstStrategy()
            val preferences = SeatPreferences(accessibilityRequired = true)
            
            val selected = strategy.selectSeats(availableSeats, 2, preferences)
            
            assertTrue(selected.all { it.type == SeatType.WHEELCHAIR })
        }
        
        @Test
        fun `AccessibleFirstStrategy excludes wheelchair seats for regular users`() {
            val strategy = AccessibleFirstStrategy()
            val preferences = SeatPreferences(accessibilityRequired = false)
            
            val selected = strategy.selectSeats(availableSeats, 5, preferences)
            
            assertTrue(selected.none { it.type == SeatType.WHEELCHAIR })
        }
        
        @Test
        fun `SocialDistancingStrategy maintains spacing`() {
            val bookedSeats = availableSeats.map { showSeat ->
                if (showSeat.seat.id == "E5") {
                    showSeat.copy(state = SeatState.BOOKED)
                } else {
                    showSeat
                }
            }
            val strategy = SocialDistancingStrategy(minSpacing = 2)
            
            val selected = strategy.selectSeats(bookedSeats, 2)
            
            assertTrue(selected.none { it.row == 'E' && it.number in 3..7 })
        }
        
        @Test
        fun `CompositeStrategy tries multiple strategies in order`() {
            val emptyList = emptyList<ShowSeat>()
            val composite = CompositeSelectionStrategy(listOf(
                AccessibleFirstStrategy(),
                BestAvailableStrategy()
            ))
            
            val selected = composite.selectSeats(availableSeats, 3)
            
            assertFalse(selected.isEmpty())
        }
        
        @Test
        fun `StrategySelector chooses appropriate strategy`() {
            val selector = StrategySelector()
            
            val accessiblePrefs = SeatPreferences(accessibilityRequired = true)
            assertTrue(selector.selectStrategy(accessiblePrefs, ShowContext("show-1")) 
                is AccessibleFirstStrategy)
            
            val socialContext = ShowContext("show-1", socialDistancingEnabled = true)
            assertTrue(selector.selectStrategy(SeatPreferences(), socialContext) 
                is SocialDistancingStrategy)
            
            assertTrue(selector.selectStrategy(SeatPreferences(), ShowContext("show-1")) 
                is BestAvailableStrategy)
        }
    }
    
    @Nested
    inner class WaitlistTest {
        
        private lateinit var waitlistManager: WaitlistManager
        private lateinit var show: Show
        private lateinit var notificationChannel: ConsoleNotificationChannel
        
        @BeforeEach
        fun setup() {
            show = createTestShow()
            notificationChannel = ConsoleNotificationChannel()
            waitlistManager = WaitlistManager()
            waitlistManager.registerShow(show)
            waitlistManager.addNotificationChannel(notificationChannel)
        }
        
        @Test
        fun `user can join waitlist`() {
            val result = waitlistManager.joinWaitlist(
                showId = show.id,
                userId = "user-1",
                seatsRequested = 2
            )
            
            assertTrue(result is WaitlistResult.Joined)
            assertEquals(1, (result as WaitlistResult.Joined).position)
        }
        
        @Test
        fun `user cannot join waitlist twice`() {
            waitlistManager.joinWaitlist(show.id, "user-1", 2)
            
            val result = waitlistManager.joinWaitlist(show.id, "user-1", 3)
            
            assertTrue(result is WaitlistResult.AlreadyOnWaitlist)
        }
        
        @Test
        fun `waitlist maintains FIFO order`() {
            waitlistManager.joinWaitlist(show.id, "user-1", 2)
            waitlistManager.joinWaitlist(show.id, "user-2", 2)
            waitlistManager.joinWaitlist(show.id, "user-3", 2)
            
            assertEquals(1, waitlistManager.getWaitlistPosition(show.id, "user-1"))
            assertEquals(2, waitlistManager.getWaitlistPosition(show.id, "user-2"))
            assertEquals(3, waitlistManager.getWaitlistPosition(show.id, "user-3"))
        }
        
        @Test
        fun `user can leave waitlist`() {
            waitlistManager.joinWaitlist(show.id, "user-1", 2)
            waitlistManager.joinWaitlist(show.id, "user-2", 2)
            
            val removed = waitlistManager.leaveWaitlist(show.id, "user-1")
            
            assertTrue(removed)
            assertNull(waitlistManager.getWaitlistPosition(show.id, "user-1"))
            assertEquals(1, waitlistManager.getWaitlistPosition(show.id, "user-2"))
        }
        
        @Test
        fun `notifications sent when seats released`() {
            waitlistManager.joinWaitlist(show.id, "user-1", 2)
            waitlistManager.joinWaitlist(show.id, "user-2", 2)
            
            val booking = Booking(
                show = show,
                seats = listOf(Seat("A1", 'A', 1), Seat("A2", 'A', 2)),
                user = createTestUser(),
                status = BookingStatus.CONFIRMED,
                totalPrice = 20.0
            )
            waitlistManager.onBookingCancelled(booking, "Test")
            
            val notifications = notificationChannel.getSentNotifications()
            assertTrue(notifications.isNotEmpty())
            assertTrue(notifications.any { it.first == "user-1" })
        }
        
        @Test
        fun `VIP priority calculator gives VIPs higher priority`() {
            val vipCalculator = VipPriorityCalculator(setOf("vip-user"))
            val waitlist = ShowWaitlist(show.id, vipCalculator)
            
            val regularEntry = WaitlistEntry(showId = show.id, userId = "regular-user", seatsRequested = 2)
            Thread.sleep(10)
            val vipEntry = WaitlistEntry(showId = show.id, userId = "vip-user", seatsRequested = 2)
            
            waitlist.addToWaitlist(regularEntry)
            waitlist.addToWaitlist(vipEntry)
            
            assertEquals(1, waitlist.getPosition("vip-user"))
            assertEquals(2, waitlist.getPosition("regular-user"))
        }
        
        @Test
        fun `waitlist observer receives events`() {
            var joinedCount = 0
            var leftCount = 0
            var notifiedCount = 0
            
            waitlistManager.addObserver(object : WaitlistObserver {
                override fun onUserJoinedWaitlist(showId: String, userId: String, position: Int) {
                    joinedCount++
                }
                override fun onUserLeftWaitlist(showId: String, userId: String) {
                    leftCount++
                }
                override fun onWaitlistNotified(showId: String, userId: String, availableSeats: Int) {
                    notifiedCount++
                }
            })
            
            waitlistManager.joinWaitlist(show.id, "user-1", 2)
            waitlistManager.leaveWaitlist(show.id, "user-1")
            
            assertEquals(1, joinedCount)
            assertEquals(1, leftCount)
        }
    }
    
    @Nested
    inner class BookingTimeoutTest {
        
        @Test
        fun `booking expires after timeout`() {
            val service = StateMachineBookingService(lockTimeoutMs = 100)
            val show = createTestShow()
            service.registerShow(show)
            
            val result = service.initiateBooking(
                show.id,
                listOf("A1", "A2"),
                createTestUser()
            )
            
            assertTrue(result is BookingResult.Success)
            val booking = (result as BookingResult.Success).booking
            
            Thread.sleep(150)
            service.processExpirations()
            
            val updatedBooking = service.getBooking(booking.id)
            assertEquals(BookingStatus.EXPIRED, updatedBooking?.status)
        }
        
        @Test
        fun `confirming expired booking fails`() {
            val service = StateMachineBookingService(lockTimeoutMs = 100)
            val show = createTestShow()
            service.registerShow(show)
            
            val initResult = service.initiateBooking(
                show.id,
                listOf("A1"),
                createTestUser()
            )
            val booking = (initResult as BookingResult.Success).booking
            
            Thread.sleep(150)
            
            val confirmResult = service.confirmBooking(
                booking.id,
                Payment.CreditCard(10.0, "1234", "VISA")
            )
            
            assertTrue(confirmResult is BookingResult.Timeout)
        }
        
        @Test
        fun `seats become available after timeout`() {
            val show = createTestShow()
            val manager = ShowSeatManager(show, lockTimeoutMs = 100)
            
            manager.lockSeats(listOf("A1"), "user-1")
            assertEquals(SeatState.LOCKED, manager.getSeatState("A1")?.state)
            
            Thread.sleep(150)
            manager.expireTimedOutLocks()
            
            assertEquals(SeatState.AVAILABLE, manager.getSeatState("A1")?.state)
        }
    }
    
    @Nested
    inner class IntegrationTest {
        
        @Test
        fun `complete booking flow`() {
            val service = StateMachineBookingService()
            val show = createTestShow()
            val user = createTestUser()
            service.registerShow(show)
            
            val initResult = service.initiateBooking(
                show.id,
                listOf("E7", "E8"),
                user
            )
            assertTrue(initResult is BookingResult.Success)
            val pendingBooking = (initResult as BookingResult.Success).booking
            assertEquals(BookingStatus.PENDING, pendingBooking.status)
            assertEquals(30.0, pendingBooking.totalPrice)
            
            val confirmResult = service.confirmBooking(
                pendingBooking.id,
                Payment.CreditCard(30.0, "4242", "VISA")
            )
            assertTrue(confirmResult is BookingResult.Success)
            val confirmedBooking = (confirmResult as BookingResult.Success).booking
            assertEquals(BookingStatus.CONFIRMED, confirmedBooking.status)
            assertNotNull(confirmedBooking.confirmedAt)
        }
        
        @Test
        fun `booking with waitlist fallback`() {
            val service = WaitlistBookingService()
            val show = createTestShow()
            val user1 = createTestUser("user-1")
            val user2 = createTestUser("user-2")
            service.registerShow(show)
            
            service.bookOrWaitlist(show.id, listOf("A1", "A2"), user1)
            
            val result = service.bookOrWaitlist(show.id, listOf("A1", "A2"), user2)
            
            assertTrue(result is BookOrWaitlistResult.AddedToWaitlist)
            assertEquals(1, (result as BookOrWaitlistResult.AddedToWaitlist).position)
        }
    }
    
    @Nested
    inner class PriceCalculationTest {
        
        @Test
        fun `price calculation includes seat type multipliers`() {
            val show = createTestShow()
            
            val regularSeats = listOf(Seat("A3", 'A', 3, SeatType.REGULAR))
            val regularPrice = regularSeats.sumOf { show.basePrice * it.type.priceMultiplier }
            assertEquals(10.0, regularPrice)
            
            val premiumSeats = listOf(Seat("E7", 'E', 7, SeatType.PREMIUM))
            val premiumPrice = premiumSeats.sumOf { show.basePrice * it.type.priceMultiplier }
            assertEquals(15.0, premiumPrice)
            
            val vipSeats = listOf(Seat("J7", 'J', 7, SeatType.VIP))
            val vipPrice = vipSeats.sumOf { show.basePrice * it.type.priceMultiplier }
            assertEquals(25.0, vipPrice)
        }
        
        @Test
        fun `booking total includes all seats`() {
            val service = StateMachineBookingService()
            val show = createTestShow()
            service.registerShow(show)
            
            val result = service.initiateBooking(
                show.id,
                listOf("J7", "J8"),
                createTestUser()
            )
            
            assertTrue(result is BookingResult.Success)
            assertEquals(50.0, (result as BookingResult.Success).booking.totalPrice)
        }
    }
}
