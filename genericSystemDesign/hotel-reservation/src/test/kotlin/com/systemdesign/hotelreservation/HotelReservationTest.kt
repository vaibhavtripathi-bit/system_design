package com.systemdesign.hotelreservation

import com.systemdesign.hotelreservation.common.*
import com.systemdesign.hotelreservation.approach_01_strategy_allocation.*
import com.systemdesign.hotelreservation.approach_02_state_machine.*
import com.systemdesign.hotelreservation.approach_03_observer_notifications.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import java.time.LocalDate
import java.util.UUID

class HotelReservationTest {
    
    private fun createTestHotel(): Hotel {
        return Hotel(
            id = "HOTEL-001",
            name = "Test Hotel",
            rooms = listOf(
                Room("101", RoomType.SINGLE, floor = 1, pricePerNight = 100.0, amenities = setOf("wifi", "tv")),
                Room("102", RoomType.SINGLE, floor = 1, pricePerNight = 100.0, amenities = setOf("wifi")),
                Room("201", RoomType.DOUBLE, floor = 2, pricePerNight = 150.0, amenities = setOf("wifi", "tv", "minibar")),
                Room("202", RoomType.DOUBLE, floor = 2, pricePerNight = 160.0, amenities = setOf("wifi", "tv", "minibar", "balcony")),
                Room("301", RoomType.SUITE, floor = 3, pricePerNight = 300.0, amenities = setOf("wifi", "tv", "minibar", "jacuzzi", "balcony")),
                Room("401", RoomType.PENTHOUSE, floor = 4, pricePerNight = 500.0, amenities = setOf("wifi", "tv", "minibar", "jacuzzi", "balcony", "butler"))
            )
        )
    }
    
    private fun createTestGuest(
        id: String = "GUEST-001",
        tier: LoyaltyTier = LoyaltyTier.STANDARD
    ): Guest {
        return Guest(
            id = id,
            name = "John Doe",
            email = "john@example.com",
            phone = "+1234567890",
            loyaltyTier = tier
        )
    }
    
    private fun createFutureDates(daysFromNow: Long = 7, nights: Long = 3): DateRange {
        val checkIn = LocalDate.now().plusDays(daysFromNow)
        val checkOut = checkIn.plusDays(nights)
        return DateRange(checkIn, checkOut)
    }
    
    @Nested
    inner class ModelsTest {
        
        @Test
        fun `DateRange calculates nights correctly`() {
            val dates = DateRange(
                LocalDate.of(2024, 1, 1),
                LocalDate.of(2024, 1, 5)
            )
            assertEquals(4, dates.nights)
        }
        
        @Test
        fun `DateRange detects overlapping ranges`() {
            val range1 = DateRange(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 5))
            val range2 = DateRange(LocalDate.of(2024, 1, 3), LocalDate.of(2024, 1, 7))
            val range3 = DateRange(LocalDate.of(2024, 1, 6), LocalDate.of(2024, 1, 10))
            
            assertTrue(range1.overlaps(range2))
            assertFalse(range1.overlaps(range3))
        }
        
        @Test
        fun `DateRange rejects invalid dates`() {
            assertThrows(IllegalArgumentException::class.java) {
                DateRange(LocalDate.of(2024, 1, 5), LocalDate.of(2024, 1, 1))
            }
        }
        
        @Test
        fun `Room matches preferences correctly`() {
            val room = Room("101", RoomType.DOUBLE, floor = 5, pricePerNight = 150.0, amenities = setOf("wifi", "tv"))
            val preferences = GuestPreferences(
                preferredFloor = 5,
                preferredRoomType = RoomType.DOUBLE,
                requiredAmenities = setOf("wifi")
            )
            
            val score = room.matchesPreferences(preferences)
            assertTrue(score > 0)
        }
        
        @Test
        fun `Reservation calculates total price with loyalty discount`() {
            val guest = createTestGuest(tier = LoyaltyTier.GOLD)
            val room = Room("101", RoomType.SINGLE, floor = 1, pricePerNight = 100.0)
            val dates = DateRange(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 4))
            
            val reservation = Reservation(
                id = "RES-001",
                guest = guest,
                room = room,
                dates = dates
            )
            
            val total = reservation.calculateTotalPrice()
            assertEquals(270.0, total, 0.01)
        }
    }
    
    @Nested
    inner class StrategyAllocationTest {
        
        private lateinit var hotel: Hotel
        private lateinit var system: StrategyBasedHotelReservation
        
        @BeforeEach
        fun setup() {
            hotel = createTestHotel()
            system = StrategyBasedHotelReservation(hotel)
        }
        
        @Test
        fun `FirstAvailableStrategy returns first matching room`() {
            system.setAllocationStrategy(FirstAvailableStrategy())
            
            val request = ReservationRequest(
                guest = createTestGuest(),
                dates = createFutureDates(),
                roomType = RoomType.SINGLE
            )
            
            val result = system.makeReservation(request)
            
            assertTrue(result is ReservationResult.Success)
            assertEquals("101", (result as ReservationResult.Success).reservation.room.number)
        }
        
        @Test
        fun `BestFitStrategy minimizes wasted capacity`() {
            val strategy = BestFitStrategy()
            
            val rooms = listOf(
                Room("A", RoomType.DOUBLE, 1, 150.0, maxOccupancy = 4),
                Room("B", RoomType.DOUBLE, 1, 150.0, maxOccupancy = 2),
                Room("C", RoomType.DOUBLE, 1, 150.0, maxOccupancy = 3)
            )
            
            val request = ReservationRequest(
                guest = createTestGuest(),
                dates = createFutureDates(),
                roomType = RoomType.DOUBLE,
                numberOfGuests = 2
            )
            
            val allocated = strategy.allocateRoom(request, rooms)
            
            assertEquals("B", allocated?.number)
        }
        
        @Test
        fun `PreferenceBasedStrategy scores rooms by preferences`() {
            system.setAllocationStrategy(PreferenceBasedStrategy())
            
            val request = ReservationRequest(
                guest = createTestGuest(),
                dates = createFutureDates(),
                roomType = RoomType.DOUBLE,
                preferences = GuestPreferences(
                    requiredAmenities = setOf("balcony")
                )
            )
            
            val result = system.makeReservation(request)
            
            assertTrue(result is ReservationResult.Success)
            val room = (result as ReservationResult.Success).reservation.room
            assertTrue(room.hasAmenity("balcony"))
        }
        
        @Test
        fun `UpgradeStrategy upgrades gold members`() {
            val strategy = UpgradeStrategy(PreferenceBasedStrategy())
            system.setAllocationStrategy(strategy)
            
            val goldGuest = createTestGuest(tier = LoyaltyTier.GOLD)
            val request = ReservationRequest(
                guest = goldGuest,
                dates = createFutureDates(),
                roomType = RoomType.DOUBLE
            )
            
            // Make a reservation - gold member requesting DOUBLE should get upgraded to SUITE
            val result = system.makeReservation(request)
            
            assertTrue(result is ReservationResult.Success)
            val room = (result as ReservationResult.Success).reservation.room
            // Gold members get upgraded from DOUBLE to SUITE when available
            assertEquals(RoomType.SUITE, room.type)
        }
        
        @Test
        fun `returns NoAvailability when room type unavailable`() {
            val dates = createFutureDates()
            
            system.makeReservation(ReservationRequest(createTestGuest("G1"), dates, RoomType.PENTHOUSE))
            val result = system.makeReservation(ReservationRequest(createTestGuest("G2"), dates, RoomType.PENTHOUSE))
            
            assertTrue(result is ReservationResult.NoAvailability)
        }
        
        @Test
        fun `rejects reservations with invalid guest count`() {
            val request = ReservationRequest(
                guest = createTestGuest(),
                dates = createFutureDates(),
                roomType = RoomType.SINGLE,
                numberOfGuests = 0
            )
            
            val result = system.makeReservation(request)
            
            assertTrue(result is ReservationResult.InvalidRequest)
        }
        
        @Test
        fun `rejects reservations with past check-in date`() {
            val request = ReservationRequest(
                guest = createTestGuest(),
                dates = DateRange(LocalDate.now().minusDays(1), LocalDate.now().plusDays(2)),
                roomType = RoomType.SINGLE
            )
            
            val result = system.makeReservation(request)
            
            assertTrue(result is ReservationResult.InvalidRequest)
        }
        
        @Test
        fun `getAvailableRooms excludes booked rooms`() {
            val dates = createFutureDates()
            
            system.makeReservation(ReservationRequest(createTestGuest(), dates, RoomType.SINGLE))
            
            val available = system.getAvailableRooms(dates, RoomType.SINGLE)
            
            assertEquals(1, available.size)
        }
        
        @Test
        fun `calculates occupancy rate correctly`() {
            val today = LocalDate.now()
            val dates = DateRange(today, today.plusDays(1))
            
            system.makeReservation(ReservationRequest(createTestGuest("G1"), dates, RoomType.SINGLE))
            system.confirmReservation(system.getReservationsForGuest("G1").first().id)
            
            val rate = system.getOccupancyRate(today)
            
            assertTrue(rate > 0)
        }
    }
    
    @Nested
    inner class StateMachineTest {
        
        private lateinit var hotel: Hotel
        private lateinit var system: StateMachineHotelReservation
        
        @BeforeEach
        fun setup() {
            hotel = createTestHotel()
            system = StateMachineHotelReservation(hotel)
        }
        
        @Test
        fun `new reservation starts in PENDING state`() {
            val result = system.createReservation(
                guest = createTestGuest(),
                roomNumber = "101",
                dates = createFutureDates()
            )
            
            assertTrue(result is ReservationResult.Success)
            assertEquals(ReservationStatus.PENDING, (result as ReservationResult.Success).reservation.status)
        }
        
        @Test
        fun `PENDING transitions to CONFIRMED with payment`() {
            val result = system.createReservation(
                guest = createTestGuest(),
                roomNumber = "101",
                dates = createFutureDates()
            )
            val reservationId = (result as ReservationResult.Success).reservation.id
            
            val confirmResult = system.confirmReservation(reservationId, paymentReceived = true)
            
            assertTrue(confirmResult is StatusChangeResult.Success)
            assertEquals(ReservationStatus.CONFIRMED, system.getReservation(reservationId)?.status)
        }
        
        @Test
        fun `PENDING cannot transition to CONFIRMED without payment`() {
            val result = system.createReservation(
                guest = createTestGuest(),
                roomNumber = "101",
                dates = createFutureDates()
            )
            val reservationId = (result as ReservationResult.Success).reservation.id
            
            val confirmResult = system.confirmReservation(reservationId, paymentReceived = false)
            
            assertTrue(confirmResult is StatusChangeResult.ValidationFailed)
        }
        
        @Test
        fun `CONFIRMED transitions to CHECKED_IN on check-in date`() {
            val checkInDate = LocalDate.now()
            val result = system.createReservation(
                guest = createTestGuest(),
                roomNumber = "101",
                dates = DateRange(checkInDate, checkInDate.plusDays(3))
            )
            val reservationId = (result as ReservationResult.Success).reservation.id
            
            system.confirmReservation(reservationId, paymentReceived = true)
            val checkInResult = system.checkIn(reservationId, checkInDate)
            
            assertTrue(checkInResult is StatusChangeResult.Success)
            assertEquals(ReservationStatus.CHECKED_IN, system.getReservation(reservationId)?.status)
        }
        
        @Test
        fun `CONFIRMED cannot check-in before reservation date`() {
            val futureDate = LocalDate.now().plusDays(7)
            val result = system.createReservation(
                guest = createTestGuest(),
                roomNumber = "101",
                dates = DateRange(futureDate, futureDate.plusDays(3))
            )
            val reservationId = (result as ReservationResult.Success).reservation.id
            
            system.confirmReservation(reservationId, paymentReceived = true)
            val checkInResult = system.checkIn(reservationId, LocalDate.now())
            
            assertTrue(checkInResult is StatusChangeResult.ValidationFailed)
        }
        
        @Test
        fun `CHECKED_IN transitions to CHECKED_OUT`() {
            val checkInDate = LocalDate.now()
            val result = system.createReservation(
                guest = createTestGuest(),
                roomNumber = "101",
                dates = DateRange(checkInDate, checkInDate.plusDays(3))
            )
            val reservationId = (result as ReservationResult.Success).reservation.id
            
            system.confirmReservation(reservationId, paymentReceived = true)
            system.checkIn(reservationId, checkInDate)
            val checkOutResult = system.checkOut(reservationId)
            
            assertTrue(checkOutResult is StatusChangeResult.Success)
            assertEquals(ReservationStatus.CHECKED_OUT, system.getReservation(reservationId)?.status)
        }
        
        @Test
        fun `early checkout is allowed`() {
            val checkInDate = LocalDate.now()
            val result = system.createReservation(
                guest = createTestGuest(),
                roomNumber = "101",
                dates = DateRange(checkInDate, checkInDate.plusDays(5))
            )
            val reservationId = (result as ReservationResult.Success).reservation.id
            
            system.confirmReservation(reservationId, paymentReceived = true)
            system.checkIn(reservationId, checkInDate)
            val checkOutResult = system.checkOut(reservationId, checkInDate.plusDays(2))
            
            assertTrue(checkOutResult is StatusChangeResult.Success)
            val reservation = system.getReservation(reservationId)!!
            assertTrue(reservation.actualCheckOut!!.isBefore(reservation.dates.checkOut))
        }
        
        @Test
        fun `CONFIRMED transitions to NO_SHOW after check-in date`() {
            val pastDate = LocalDate.now().minusDays(1)
            val result = system.createReservation(
                guest = createTestGuest(),
                roomNumber = "101",
                dates = DateRange(pastDate, pastDate.plusDays(3))
            )
            val reservationId = (result as ReservationResult.Success).reservation.id
            
            val reservation = system.getReservation(reservationId)!!
            reservation.status = ReservationStatus.CONFIRMED
            reservation.paymentConfirmed = true
            
            val noShowResult = system.markAsNoShow(reservationId, LocalDate.now())
            
            assertTrue(noShowResult is StatusChangeResult.Success)
            assertEquals(ReservationStatus.NO_SHOW, system.getReservation(reservationId)?.status)
        }
        
        @Test
        fun `cannot mark as no-show before check-in date`() {
            val futureDate = LocalDate.now().plusDays(7)
            val result = system.createReservation(
                guest = createTestGuest(),
                roomNumber = "101",
                dates = DateRange(futureDate, futureDate.plusDays(3))
            )
            val reservationId = (result as ReservationResult.Success).reservation.id
            
            system.confirmReservation(reservationId, paymentReceived = true)
            val noShowResult = system.markAsNoShow(reservationId, LocalDate.now())
            
            assertTrue(noShowResult is StatusChangeResult.ValidationFailed)
        }
        
        @Test
        fun `PENDING can be cancelled`() {
            val result = system.createReservation(
                guest = createTestGuest(),
                roomNumber = "101",
                dates = createFutureDates()
            )
            val reservationId = (result as ReservationResult.Success).reservation.id
            
            val cancelResult = system.cancelReservation(reservationId)
            
            assertTrue(cancelResult is StatusChangeResult.Success)
            assertEquals(ReservationStatus.CANCELLED, system.getReservation(reservationId)?.status)
        }
        
        @Test
        fun `CHECKED_IN cannot be cancelled without force`() {
            val checkInDate = LocalDate.now()
            val result = system.createReservation(
                guest = createTestGuest(),
                roomNumber = "101",
                dates = DateRange(checkInDate, checkInDate.plusDays(3))
            )
            val reservationId = (result as ReservationResult.Success).reservation.id
            
            system.confirmReservation(reservationId, paymentReceived = true)
            system.checkIn(reservationId, checkInDate)
            val cancelResult = system.cancelReservation(reservationId)
            
            assertTrue(cancelResult is StatusChangeResult.InvalidTransition)
        }
        
        @Test
        fun `state history is recorded`() {
            val result = system.createReservation(
                guest = createTestGuest(),
                roomNumber = "101",
                dates = createFutureDates()
            )
            val reservationId = (result as ReservationResult.Success).reservation.id
            
            system.confirmReservation(reservationId, paymentReceived = true)
            
            val history = system.getReservationHistory(reservationId)
            
            assertTrue(history.size >= 2)
            assertEquals(ReservationStatus.CONFIRMED, history.last().toStatus)
        }
        
        @Test
        fun `getValidNextStates returns correct transitions`() {
            val result = system.createReservation(
                guest = createTestGuest(),
                roomNumber = "101",
                dates = createFutureDates()
            )
            val reservationId = (result as ReservationResult.Success).reservation.id
            
            val validStates = system.getValidNextStates(reservationId)
            
            assertTrue(ReservationStatus.CONFIRMED in validStates)
            assertTrue(ReservationStatus.CANCELLED in validStates)
            assertFalse(ReservationStatus.CHECKED_IN in validStates)
        }
        
        @Test
        fun `early checkout refund is calculated correctly`() {
            val checkInDate = LocalDate.now()
            val result = system.createReservation(
                guest = createTestGuest(),
                roomNumber = "101",
                dates = DateRange(checkInDate, checkInDate.plusDays(5))
            )
            val reservationId = (result as ReservationResult.Success).reservation.id
            
            system.confirmReservation(reservationId, paymentReceived = true)
            system.checkIn(reservationId, checkInDate)
            system.checkOut(reservationId, checkInDate.plusDays(2))
            
            val reservation = system.getReservation(reservationId)!!
            val refund = system.calculateEarlyCheckoutRefund(reservation)
            
            assertEquals(150.0, refund, 0.01)
        }
    }
    
    @Nested
    inner class DateConflictTest {
        
        private lateinit var hotel: Hotel
        private lateinit var system: StateMachineHotelReservation
        
        @BeforeEach
        fun setup() {
            hotel = createTestHotel()
            system = StateMachineHotelReservation(hotel)
        }
        
        @Test
        fun `prevents double booking same room`() {
            val dates = createFutureDates()
            
            val result1 = system.createReservation(
                guest = createTestGuest("G1"),
                roomNumber = "101",
                dates = dates
            )
            
            val result2 = system.createReservation(
                guest = createTestGuest("G2"),
                roomNumber = "101",
                dates = dates
            )
            
            assertTrue(result1 is ReservationResult.Success)
            assertTrue(result2 is ReservationResult.Conflict)
        }
        
        @Test
        fun `allows booking same room for non-overlapping dates`() {
            val dates1 = DateRange(LocalDate.now().plusDays(1), LocalDate.now().plusDays(3))
            val dates2 = DateRange(LocalDate.now().plusDays(5), LocalDate.now().plusDays(7))
            
            val result1 = system.createReservation(
                guest = createTestGuest("G1"),
                roomNumber = "101",
                dates = dates1
            )
            
            val result2 = system.createReservation(
                guest = createTestGuest("G2"),
                roomNumber = "101",
                dates = dates2
            )
            
            assertTrue(result1 is ReservationResult.Success)
            assertTrue(result2 is ReservationResult.Success)
        }
        
        @Test
        fun `detects partial overlap`() {
            val dates1 = DateRange(LocalDate.now().plusDays(1), LocalDate.now().plusDays(5))
            val dates2 = DateRange(LocalDate.now().plusDays(3), LocalDate.now().plusDays(7))
            
            system.createReservation(
                guest = createTestGuest("G1"),
                roomNumber = "101",
                dates = dates1
            )
            
            val result = system.createReservation(
                guest = createTestGuest("G2"),
                roomNumber = "101",
                dates = dates2
            )
            
            assertTrue(result is ReservationResult.Conflict)
        }
        
        @Test
        fun `allows booking different rooms for same dates`() {
            val dates = createFutureDates()
            
            val result1 = system.createReservation(
                guest = createTestGuest("G1"),
                roomNumber = "101",
                dates = dates
            )
            
            val result2 = system.createReservation(
                guest = createTestGuest("G2"),
                roomNumber = "102",
                dates = dates
            )
            
            assertTrue(result1 is ReservationResult.Success)
            assertTrue(result2 is ReservationResult.Success)
        }
        
        @Test
        fun `cancelled reservation frees the room`() {
            val dates = createFutureDates()
            
            val result1 = system.createReservation(
                guest = createTestGuest("G1"),
                roomNumber = "101",
                dates = dates
            )
            val reservationId = (result1 as ReservationResult.Success).reservation.id
            
            system.cancelReservation(reservationId)
            
            val result2 = system.createReservation(
                guest = createTestGuest("G2"),
                roomNumber = "101",
                dates = dates
            )
            
            assertTrue(result2 is ReservationResult.Success)
        }
    }
    
    @Nested
    inner class ObserverNotificationsTest {
        
        private lateinit var hotel: Hotel
        private lateinit var system: ObserverBasedHotelReservation
        private lateinit var emailNotifier: EmailNotifier
        private lateinit var smsNotifier: SMSNotifier
        private lateinit var analyticsTracker: AnalyticsTracker
        
        @BeforeEach
        fun setup() {
            hotel = createTestHotel()
            system = ObserverBasedHotelReservation(hotel)
            emailNotifier = EmailNotifier()
            smsNotifier = SMSNotifier()
            analyticsTracker = AnalyticsTracker()
            
            system.addObserver(emailNotifier)
            system.addObserver(smsNotifier)
            system.addObserver(analyticsTracker)
        }
        
        @Test
        fun `observers are registered correctly`() {
            assertEquals(3, system.getObserverCount())
        }
        
        @Test
        fun `observers can be removed`() {
            system.removeObserver(smsNotifier)
            assertEquals(2, system.getObserverCount())
        }
        
        @Test
        fun `email sent on reservation creation`() {
            system.createReservation(
                guest = createTestGuest(),
                roomNumber = "101",
                dates = createFutureDates()
            )
            
            assertEquals(1, emailNotifier.sentEmails.size)
            assertTrue(emailNotifier.sentEmails.first().type.contains("Pending"))
        }
        
        @Test
        fun `email and SMS sent on confirmation`() {
            val result = system.createReservation(
                guest = createTestGuest(),
                roomNumber = "101",
                dates = createFutureDates()
            )
            val reservationId = (result as ReservationResult.Success).reservation.id
            
            system.confirmReservation(reservationId)
            
            assertTrue(emailNotifier.sentEmails.any { it.type.contains("Confirmed") })
            assertTrue(smsNotifier.sentMessages.any { it.type == "CONFIRMED" })
        }
        
        @Test
        fun `notifications sent on check-in`() {
            val checkInDate = LocalDate.now()
            val result = system.createReservation(
                guest = createTestGuest(),
                roomNumber = "101",
                dates = DateRange(checkInDate, checkInDate.plusDays(3))
            )
            val reservationId = (result as ReservationResult.Success).reservation.id
            
            system.confirmReservation(reservationId)
            system.checkIn(reservationId)
            
            assertTrue(emailNotifier.sentEmails.any { it.type.contains("Welcome") })
            assertTrue(smsNotifier.sentMessages.any { it.type == "CHECK_IN" })
        }
        
        @Test
        fun `notifications sent on check-out`() {
            val checkInDate = LocalDate.now()
            val result = system.createReservation(
                guest = createTestGuest(),
                roomNumber = "101",
                dates = DateRange(checkInDate, checkInDate.plusDays(3))
            )
            val reservationId = (result as ReservationResult.Success).reservation.id
            
            system.confirmReservation(reservationId)
            system.checkIn(reservationId)
            system.checkOut(reservationId)
            
            assertTrue(emailNotifier.sentEmails.any { it.type.contains("Thank You") })
        }
        
        @Test
        fun `notifications sent on cancellation`() {
            val result = system.createReservation(
                guest = createTestGuest(),
                roomNumber = "101",
                dates = createFutureDates()
            )
            val reservationId = (result as ReservationResult.Success).reservation.id
            
            system.cancelReservation(reservationId, "Changed plans")
            
            assertTrue(emailNotifier.sentEmails.any { it.type.contains("Cancelled") })
            assertTrue(smsNotifier.sentMessages.any { it.type == "CANCELLED" })
        }
        
        @Test
        fun `analytics tracks reservation lifecycle`() {
            val result = system.createReservation(
                guest = createTestGuest(),
                roomNumber = "101",
                dates = createFutureDates()
            )
            val reservationId = (result as ReservationResult.Success).reservation.id
            
            system.confirmReservation(reservationId)
            
            val events = analyticsTracker.events
            assertTrue(events.any { it.eventType == "reservation_created" })
            assertTrue(events.any { it.eventType == "reservation_confirmed" })
        }
        
        @Test
        fun `analytics tracks no-shows`() {
            val pastDate = LocalDate.now().minusDays(1)
            val result = system.createReservation(
                guest = createTestGuest(),
                roomNumber = "101",
                dates = DateRange(pastDate, pastDate.plusDays(3))
            )
            val reservationId = (result as ReservationResult.Success).reservation.id
            
            val reservation = system.getReservation(reservationId)!!
            reservation.status = ReservationStatus.CONFIRMED
            
            system.markAsNoShow(reservationId)
            
            assertTrue(analyticsTracker.events.any { it.eventType == "no_show" })
        }
        
        @Test
        fun `analytics calculates revenue by room type`() {
            system.createReservation(createTestGuest("G1"), "101", createFutureDates())
            system.createReservation(createTestGuest("G2"), "301", createFutureDates())
            
            val reservations = system.getActiveReservations()
            reservations.forEach { system.confirmReservation(it.id) }
            
            val revenueByType = analyticsTracker.getRevenueByRoomType()
            
            assertTrue(revenueByType.containsKey(RoomType.SINGLE))
            assertTrue(revenueByType.containsKey(RoomType.SUITE))
        }
        
        @Test
        fun `upcoming reminders are sent`() {
            val checkInDate = LocalDate.now().plusDays(3)
            val result = system.createReservation(
                guest = createTestGuest(),
                roomNumber = "101",
                dates = DateRange(checkInDate, checkInDate.plusDays(2))
            )
            val reservationId = (result as ReservationResult.Success).reservation.id
            
            system.confirmReservation(reservationId)
            system.sendUpcomingReminders()
            
            assertTrue(emailNotifier.sentEmails.any { it.type.contains("Coming Up") })
            assertTrue(smsNotifier.sentMessages.any { it.type == "REMINDER" })
        }
        
        @Test
        fun `housekeeping notifier creates tasks`() {
            val housekeeping = HousekeepingNotifier()
            system.addObserver(housekeeping)
            
            val checkInDate = LocalDate.now()
            val result = system.createReservation(
                guest = createTestGuest(tier = LoyaltyTier.GOLD),
                roomNumber = "101",
                dates = DateRange(checkInDate, checkInDate.plusDays(3))
            )
            val reservationId = (result as ReservationResult.Success).reservation.id
            
            system.confirmReservation(reservationId)
            system.checkIn(reservationId)
            system.checkOut(reservationId)
            
            assertTrue(housekeeping.tasks.any { it.taskType == HousekeepingNotifier.TaskType.PREPARE_ROOM })
            assertTrue(housekeeping.tasks.any { it.taskType == HousekeepingNotifier.TaskType.CLEAN_AFTER_CHECKOUT })
        }
        
        @Test
        fun `VIP guests get high priority housekeeping`() {
            val housekeeping = HousekeepingNotifier()
            system.addObserver(housekeeping)
            
            val result = system.createReservation(
                guest = createTestGuest(tier = LoyaltyTier.PLATINUM),
                roomNumber = "101",
                dates = createFutureDates()
            )
            val reservationId = (result as ReservationResult.Success).reservation.id
            
            system.confirmReservation(reservationId)
            
            val prepTask = housekeeping.tasks.find { it.taskType == HousekeepingNotifier.TaskType.PREPARE_ROOM }
            assertEquals(HousekeepingNotifier.Priority.HIGH, prepTask?.priority)
        }
    }
    
    @Nested
    inner class OverbookingPreventionTest {
        
        private lateinit var hotel: Hotel
        
        @BeforeEach
        fun setup() {
            hotel = createTestHotel()
        }
        
        @Test
        fun `strategy system prevents overbooking`() {
            val system = StrategyBasedHotelReservation(hotel)
            val dates = createFutureDates()
            
            hotel.rooms.filter { it.type == RoomType.SINGLE }.forEach { _ ->
                system.makeReservation(ReservationRequest(
                    guest = createTestGuest(UUID.randomUUID().toString()),
                    dates = dates,
                    roomType = RoomType.SINGLE
                ))
            }
            
            val result = system.makeReservation(ReservationRequest(
                guest = createTestGuest("extra"),
                dates = dates,
                roomType = RoomType.SINGLE
            ))
            
            assertTrue(result is ReservationResult.NoAvailability)
        }
        
        @Test
        fun `state machine system prevents overbooking`() {
            val system = StateMachineHotelReservation(hotel)
            val dates = createFutureDates()
            
            val singleRooms = hotel.rooms.filter { it.type == RoomType.SINGLE }
            singleRooms.forEach { room ->
                system.createReservation(
                    guest = createTestGuest(UUID.randomUUID().toString()),
                    roomNumber = room.number,
                    dates = dates
                )
            }
            
            val result = system.createReservation(
                guest = createTestGuest("extra"),
                roomNumber = singleRooms.first().number,
                dates = dates
            )
            
            assertTrue(result is ReservationResult.Conflict)
        }
        
        @Test
        fun `observer system prevents overbooking`() {
            val system = ObserverBasedHotelReservation(hotel)
            val dates = createFutureDates()
            
            system.createReservation(createTestGuest("G1"), "101", dates)
            
            val result = system.createReservation(createTestGuest("G2"), "101", dates)
            
            assertTrue(result is ReservationResult.NoAvailability)
        }
    }
    
    @Nested
    inner class RoomAvailabilityTest {
        
        private lateinit var hotel: Hotel
        private lateinit var system: StrategyBasedHotelReservation
        
        @BeforeEach
        fun setup() {
            hotel = createTestHotel()
            system = StrategyBasedHotelReservation(hotel)
        }
        
        @Test
        fun `returns all rooms when no reservations exist`() {
            val dates = createFutureDates()
            val available = system.getAvailableRooms(dates)
            
            assertEquals(hotel.rooms.size, available.size)
        }
        
        @Test
        fun `filters by room type`() {
            val dates = createFutureDates()
            val singles = system.getAvailableRooms(dates, RoomType.SINGLE)
            
            assertTrue(singles.all { it.type == RoomType.SINGLE })
        }
        
        @Test
        fun `excludes rooms with active reservations`() {
            val dates = createFutureDates()
            
            system.makeReservation(ReservationRequest(createTestGuest(), dates, RoomType.SINGLE))
            
            val available = system.getAvailableRooms(dates, RoomType.SINGLE)
            
            assertEquals(1, available.size)
        }
        
        @Test
        fun `room becomes available after check-out date`() {
            val dates1 = DateRange(LocalDate.now().plusDays(1), LocalDate.now().plusDays(3))
            val dates2 = DateRange(LocalDate.now().plusDays(5), LocalDate.now().plusDays(7))
            
            system.makeReservation(ReservationRequest(createTestGuest(), dates1, RoomType.SINGLE))
            
            val available = system.getAvailableRooms(dates2, RoomType.SINGLE)
            
            assertEquals(2, available.size)
        }
        
        @Test
        fun `isRoomAvailable returns correct status`() {
            val dates = createFutureDates()
            
            assertTrue(system.isRoomAvailable("101", dates))
            
            system.makeReservation(ReservationRequest(createTestGuest(), dates, RoomType.SINGLE))
            
            assertFalse(system.isRoomAvailable("101", dates))
        }
    }
    
    @Nested
    inner class StatisticsTest {
        
        private lateinit var hotel: Hotel
        private lateinit var system: StrategyBasedHotelReservation
        
        @BeforeEach
        fun setup() {
            hotel = createTestHotel()
            system = StrategyBasedHotelReservation(hotel)
        }
        
        @Test
        fun `statistics reflect current state`() {
            val today = LocalDate.now()
            val dates = DateRange(today, today.plusDays(2))
            
            system.makeReservation(ReservationRequest(createTestGuest("G1"), dates, RoomType.SINGLE))
            system.makeReservation(ReservationRequest(createTestGuest("G2"), dates, RoomType.DOUBLE))
            
            val reservations = system.getReservationsForDate(today)
            assertEquals(2, reservations.size)
            
            val stats = system.getStatistics()
            assertEquals(hotel.rooms.size, stats.totalRooms)
            assertEquals(2, stats.reservationsByStatus[ReservationStatus.PENDING])
        }
    }
    
    companion object {
        private fun UUID.randomUUID() = java.util.UUID.randomUUID()
    }
}
