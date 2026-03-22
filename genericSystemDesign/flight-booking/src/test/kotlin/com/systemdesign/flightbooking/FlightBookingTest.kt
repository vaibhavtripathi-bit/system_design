package com.systemdesign.flightbooking

import com.systemdesign.flightbooking.common.*
import com.systemdesign.flightbooking.approach_01_strategy_seat.*
import com.systemdesign.flightbooking.approach_02_state_machine.*
import com.systemdesign.flightbooking.approach_03_builder_itinerary.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.LocalDateTime

class FlightBookingTest {
    
    private fun createTestAirports(): Pair<Airport, Airport> {
        val jfk = Airport("JFK", "John F. Kennedy International", "New York", "USA")
        val lax = Airport("LAX", "Los Angeles International", "Los Angeles", "USA")
        return jfk to lax
    }
    
    private fun createTestSeats(): List<Seat> {
        return listOf(
            Seat("1A", SeatClass.FIRST, SeatType.WINDOW, 500.0, row = 1, hasExtraLegroom = true),
            Seat("1B", SeatClass.FIRST, SeatType.MIDDLE, 500.0, row = 1, hasExtraLegroom = true),
            Seat("1C", SeatClass.FIRST, SeatType.AISLE, 500.0, row = 1, hasExtraLegroom = true),
            Seat("5A", SeatClass.BUSINESS, SeatType.WINDOW, 300.0, row = 5),
            Seat("5B", SeatClass.BUSINESS, SeatType.MIDDLE, 300.0, row = 5),
            Seat("5C", SeatClass.BUSINESS, SeatType.AISLE, 300.0, row = 5),
            Seat("10A", SeatClass.ECONOMY, SeatType.WINDOW, 100.0, row = 10),
            Seat("10B", SeatClass.ECONOMY, SeatType.MIDDLE, 80.0, row = 10),
            Seat("10C", SeatClass.ECONOMY, SeatType.AISLE, 100.0, row = 10),
            Seat("11A", SeatClass.ECONOMY, SeatType.WINDOW, 100.0, row = 11, isEmergencyExit = true, hasExtraLegroom = true),
            Seat("11B", SeatClass.ECONOMY, SeatType.MIDDLE, 80.0, row = 11, isEmergencyExit = true, hasExtraLegroom = true),
            Seat("11C", SeatClass.ECONOMY, SeatType.AISLE, 100.0, row = 11, isEmergencyExit = true, hasExtraLegroom = true),
            Seat("15A", SeatClass.ECONOMY, SeatType.WINDOW, 100.0, row = 15),
            Seat("15B", SeatClass.ECONOMY, SeatType.MIDDLE, 80.0, row = 15),
            Seat("15C", SeatClass.ECONOMY, SeatType.AISLE, 100.0, row = 15),
            Seat("20A", SeatClass.ECONOMY, SeatType.WINDOW, 100.0, row = 20),
            Seat("20B", SeatClass.ECONOMY, SeatType.MIDDLE, 80.0, row = 20),
            Seat("20C", SeatClass.ECONOMY, SeatType.AISLE, 100.0, row = 20)
        )
    }
    
    private fun createTestFlight(
        origin: Airport? = null,
        destination: Airport? = null,
        departure: LocalDateTime = LocalDateTime.now().plusDays(7),
        seats: List<Seat>? = null
    ): Flight {
        val (jfk, lax) = createTestAirports()
        return Flight(
            number = "AA100",
            airline = "American Airlines",
            origin = origin ?: jfk,
            destination = destination ?: lax,
            departure = departure,
            arrival = departure.plusHours(5),
            seats = seats ?: createTestSeats(),
            aircraft = "Boeing 777"
        )
    }
    
    private fun createTestPassenger(id: String = "P001"): Passenger {
        return Passenger(
            id = id,
            name = "John Doe",
            passport = "AB123456",
            email = "john@example.com"
        )
    }
    
    @Nested
    inner class SeatAvailabilityTest {
        
        @Test
        fun `flight returns all seats as available when none booked`() {
            val flight = createTestFlight()
            
            val available = flight.getAvailableSeats()
            
            assertEquals(flight.seats.size, available.size)
        }
        
        @Test
        fun `flight excludes booked seats from available`() {
            val flight = createTestFlight()
            val bookedSeats = setOf("1A", "10A", "15C")
            
            val available = flight.getAvailableSeats(bookedSeats)
            
            assertEquals(flight.seats.size - 3, available.size)
            assertTrue(available.none { it.number in bookedSeats })
        }
        
        @Test
        fun `seat reports availability correctly`() {
            val seat = Seat("10A", SeatClass.ECONOMY, SeatType.WINDOW, 100.0)
            
            assertTrue(seat.isAvailable(emptySet()))
            assertTrue(seat.isAvailable(setOf("10B", "10C")))
            assertFalse(seat.isAvailable(setOf("10A")))
        }
        
        @Test
        fun `flight filters seats by class`() {
            val flight = createTestFlight()
            
            val economySeats = flight.getSeatsByClass(SeatClass.ECONOMY)
            val businessSeats = flight.getSeatsByClass(SeatClass.BUSINESS)
            val firstSeats = flight.getSeatsByClass(SeatClass.FIRST)
            
            assertTrue(economySeats.all { it.seatClass == SeatClass.ECONOMY })
            assertTrue(businessSeats.all { it.seatClass == SeatClass.BUSINESS })
            assertTrue(firstSeats.all { it.seatClass == SeatClass.FIRST })
        }
        
        @Test
        fun `flight filters seats by type`() {
            val flight = createTestFlight()
            
            val windowSeats = flight.getSeatsByType(SeatType.WINDOW)
            val aisleSeats = flight.getSeatsByType(SeatType.AISLE)
            
            assertTrue(windowSeats.all { it.type == SeatType.WINDOW })
            assertTrue(aisleSeats.all { it.type == SeatType.AISLE })
        }
    }
    
    @Nested
    inner class SeatSelectionStrategyTest {
        
        private lateinit var selector: SeatSelector
        private lateinit var flight: Flight
        
        @BeforeEach
        fun setup() {
            selector = SeatSelector()
            flight = createTestFlight()
        }
        
        @Test
        fun `window preference strategy selects window seat`() {
            selector.setStrategy(WindowPreferenceStrategy())
            val preferences = SeatPreferences(preferredClass = SeatClass.ECONOMY)
            
            val result = selector.selectSeat(flight, preferences)
            
            assertTrue(result is SeatSelectionResult.Selected)
            val selected = (result as SeatSelectionResult.Selected).seat
            assertEquals(SeatType.WINDOW, selected.type)
        }
        
        @Test
        fun `aisle preference strategy selects aisle seat`() {
            selector.setStrategy(AislePreferenceStrategy())
            val preferences = SeatPreferences(preferredClass = SeatClass.ECONOMY)
            
            val result = selector.selectSeat(flight, preferences)
            
            assertTrue(result is SeatSelectionResult.Selected)
            val selected = (result as SeatSelectionResult.Selected).seat
            assertEquals(SeatType.AISLE, selected.type)
        }
        
        @Test
        fun `extra legroom strategy prefers legroom seats`() {
            selector.setStrategy(ExtraLegroomStrategy())
            val preferences = SeatPreferences(
                preferredClass = SeatClass.ECONOMY,
                preferExtraLegroom = true
            )
            
            val result = selector.selectSeat(flight, preferences)
            
            assertTrue(result is SeatSelectionResult.Selected)
            val selected = (result as SeatSelectionResult.Selected).seat
            assertTrue(selected.hasExtraLegroom)
        }
        
        @Test
        fun `budget strategy prefers cheaper seats`() {
            selector.setStrategy(BudgetStrategy())
            val preferences = SeatPreferences(preferredClass = SeatClass.ECONOMY)
            
            val result = selector.selectSeat(flight, preferences)
            
            assertTrue(result is SeatSelectionResult.Selected)
            val selected = (result as SeatSelectionResult.Selected).seat
            assertEquals(80.0, selected.price)
        }
        
        @Test
        fun `family grouping strategy finds adjacent seats`() {
            selector.setStrategy(FamilyGroupingStrategy())
            val preferences = SeatPreferences(
                preferredClass = SeatClass.ECONOMY,
                passengerCount = 3,
                keepTogether = true
            )
            
            val result = selector.selectSeatsForGroup(flight, preferences, 3)
            
            assertTrue(result is MultiSeatSelectionResult.Selected)
            val selected = (result as MultiSeatSelectionResult.Selected).seats
            assertEquals(3, selected.size)
            assertEquals(1, selected.map { it.row }.distinct().size)
        }
        
        @Test
        fun `returns no match when requested class unavailable`() {
            val economyOnlySeats = createTestSeats().filter { it.seatClass == SeatClass.ECONOMY }
            val economyOnlyFlight = createTestFlight(seats = economyOnlySeats)
            val preferences = SeatPreferences(preferredClass = SeatClass.FIRST)
            
            val result = selector.selectSeat(economyOnlyFlight, preferences)
            
            assertTrue(result is SeatSelectionResult.NoMatch)
        }
        
        @Test
        fun `returns partial match when not enough seats for group`() {
            val limitedSeats = createTestSeats().take(2)
            val limitedFlight = createTestFlight(seats = limitedSeats)
            selector.setStrategy(FamilyGroupingStrategy())
            val preferences = SeatPreferences(preferredClass = SeatClass.FIRST)
            
            val result = selector.selectSeatsForGroup(limitedFlight, preferences, 5)
            
            assertTrue(result is MultiSeatSelectionResult.PartialMatch || result is MultiSeatSelectionResult.NoMatch)
        }
        
        @Test
        fun `strategy registry returns registered strategies`() {
            val registry = SeatSelectionStrategyRegistry()
            
            assertNotNull(registry.get("window"))
            assertNotNull(registry.get("aisle"))
            assertNotNull(registry.get("family"))
            assertNotNull(registry.get("legroom"))
            assertNotNull(registry.get("budget"))
        }
        
        @Test
        fun `selector recommends strategy based on preferences`() {
            val familyPrefs = SeatPreferences(passengerCount = 3, keepTogether = true)
            val windowPrefs = SeatPreferences(preferredType = SeatType.WINDOW)
            val legroomPrefs = SeatPreferences(preferExtraLegroom = true)
            
            assertTrue(selector.recommendStrategy(familyPrefs) is FamilyGroupingStrategy)
            assertTrue(selector.recommendStrategy(windowPrefs) is WindowPreferenceStrategy)
            assertTrue(selector.recommendStrategy(legroomPrefs) is ExtraLegroomStrategy)
        }
        
        @Test
        fun `composite strategy combines multiple strategies`() {
            val composite = CompositeStrategy(listOf(
                WindowPreferenceStrategy() to 0.7,
                ExtraLegroomStrategy() to 0.3
            ))
            selector.setStrategy(composite)
            val preferences = SeatPreferences(preferredClass = SeatClass.ECONOMY)
            
            val result = selector.selectSeat(flight, preferences)
            
            assertTrue(result is SeatSelectionResult.Selected)
        }
    }
    
    @Nested
    inner class BookingStateTransitionTest {
        
        private lateinit var stateMachine: BookingStateMachine
        private lateinit var passenger: Passenger
        private lateinit var flight: Flight
        
        @BeforeEach
        fun setup() {
            stateMachine = BookingStateMachine()
            passenger = createTestPassenger()
            flight = createTestFlight()
        }
        
        @Test
        fun `new booking starts in SEARCHING state`() {
            val booking = stateMachine.createBooking(
                "BK001",
                passenger,
                listOf(flight),
                PriceBreakdown(200.0, 30.0, 15.0)
            )
            
            assertEquals(BookingStatus.SEARCHING, booking.status)
        }
        
        @Test
        fun `selecting seats transitions to SELECTED state`() {
            stateMachine.createBooking("BK001", passenger, listOf(flight), PriceBreakdown(200.0, 30.0, 15.0))
            val seat = flight.seats.first()
            
            val result = stateMachine.selectSeats("BK001", listOf(seat))
            
            assertTrue(result is TransitionResult.Success)
            assertEquals(BookingStatus.SELECTED, (result as TransitionResult.Success).newStatus)
        }
        
        @Test
        fun `proceeding to payment transitions to PAYMENT_PENDING state`() {
            stateMachine.createBooking("BK001", passenger, listOf(flight), PriceBreakdown(200.0, 30.0, 15.0))
            stateMachine.selectSeats("BK001", listOf(flight.seats.first()))
            
            val result = stateMachine.proceedToPayment("BK001")
            
            assertTrue(result is TransitionResult.Success)
            assertEquals(BookingStatus.PAYMENT_PENDING, (result as TransitionResult.Success).newStatus)
        }
        
        @Test
        fun `confirming payment transitions to CONFIRMED state`() {
            val price = PriceBreakdown(200.0, 30.0, 15.0)
            stateMachine.createBooking("BK001", passenger, listOf(flight), price)
            stateMachine.selectSeats("BK001", listOf(flight.seats.first()))
            stateMachine.proceedToPayment("BK001")
            
            val result = stateMachine.confirmPayment("BK001", price.total)
            
            assertTrue(result is TransitionResult.Success)
            assertEquals(BookingStatus.CONFIRMED, (result as TransitionResult.Success).newStatus)
        }
        
        @Test
        fun `insufficient payment amount fails confirmation`() {
            val price = PriceBreakdown(200.0, 30.0, 15.0)
            stateMachine.createBooking("BK001", passenger, listOf(flight), price)
            stateMachine.selectSeats("BK001", listOf(flight.seats.first()))
            stateMachine.proceedToPayment("BK001")
            
            val result = stateMachine.confirmPayment("BK001", 100.0)
            
            assertTrue(result is TransitionResult.ConditionNotMet)
        }
        
        @Test
        fun `cannot proceed to payment without seats`() {
            stateMachine.createBooking("BK001", passenger, listOf(flight), PriceBreakdown(200.0, 30.0, 15.0))
            
            val result = stateMachine.proceedToPayment("BK001")
            
            assertTrue(result is TransitionResult.ConditionNotMet)
        }
        
        @Test
        fun `invalid transition is rejected`() {
            stateMachine.createBooking("BK001", passenger, listOf(flight), PriceBreakdown(200.0, 30.0, 15.0))
            
            val result = stateMachine.transition("BK001", BookingStatus.CONFIRMED)
            
            assertTrue(result is TransitionResult.InvalidTransition)
        }
        
        @Test
        fun `cannot check in more than 24 hours before departure`() {
            val futureFlight = createTestFlight(departure = LocalDateTime.now().plusDays(5))
            val price = PriceBreakdown(200.0, 30.0, 15.0)
            stateMachine.createBooking("BK001", passenger, listOf(futureFlight), price)
            stateMachine.selectSeats("BK001", listOf(futureFlight.seats.first()))
            stateMachine.proceedToPayment("BK001")
            stateMachine.confirmPayment("BK001", price.total)
            
            val result = stateMachine.checkIn("BK001")
            
            assertTrue(result is TransitionResult.ConditionNotMet)
        }
        
        @Test
        fun `state history is recorded`() {
            stateMachine.createBooking("BK001", passenger, listOf(flight), PriceBreakdown(200.0, 30.0, 15.0))
            stateMachine.selectSeats("BK001", listOf(flight.seats.first()))
            
            val history = stateMachine.getStateHistory("BK001")
            
            assertEquals(2, history.size)
            assertEquals(BookingStatus.SEARCHING, history[0].first)
            assertEquals(BookingStatus.SELECTED, history[1].first)
        }
        
        @Test
        fun `seat holds are created when selecting seats`() {
            stateMachine.createBooking("BK001", passenger, listOf(flight), PriceBreakdown(200.0, 30.0, 15.0))
            val seat = flight.seats.first()
            stateMachine.selectSeats("BK001", listOf(seat))
            
            val holds = stateMachine.getSeatHolds("BK001")
            
            assertEquals(1, holds.size)
            assertEquals(seat.number, holds[0].seatNumber)
        }
        
        @Test
        fun `payment deadline is set when proceeding to payment`() {
            stateMachine.createBooking("BK001", passenger, listOf(flight), PriceBreakdown(200.0, 30.0, 15.0))
            stateMachine.selectSeats("BK001", listOf(flight.seats.first()))
            stateMachine.proceedToPayment("BK001")
            
            val deadline = stateMachine.getPaymentDeadline("BK001")
            
            assertNotNull(deadline)
            assertTrue(deadline!!.isAfter(LocalDateTime.now()))
        }
    }
    
    @Nested
    inner class CancellationAndRefundTest {
        
        private lateinit var stateMachine: BookingStateMachine
        private lateinit var passenger: Passenger
        
        @BeforeEach
        fun setup() {
            stateMachine = BookingStateMachine()
            passenger = createTestPassenger()
        }
        
        @Test
        fun `cancelling unconfirmed booking gives full refund`() {
            val flight = createTestFlight()
            val price = PriceBreakdown(200.0, 30.0, 15.0)
            stateMachine.createBooking("BK001", passenger, listOf(flight), price)
            stateMachine.selectSeats("BK001", listOf(flight.seats.first()))
            
            val (result, refund) = stateMachine.cancel("BK001")
            
            assertTrue(result is TransitionResult.Success)
            assertNotNull(refund)
            assertEquals(price.total, refund!!.refundAmount, 0.01)
            assertEquals(0.0, refund.penalty, 0.01)
        }
        
        @Test
        fun `cancelling confirmed booking applies refund policy`() {
            val futureFlight = createTestFlight(departure = LocalDateTime.now().plusDays(5))
            val price = PriceBreakdown(200.0, 30.0, 15.0)
            stateMachine.createBooking("BK001", passenger, listOf(futureFlight), price)
            stateMachine.selectSeats("BK001", listOf(futureFlight.seats.first()))
            stateMachine.proceedToPayment("BK001")
            stateMachine.confirmPayment("BK001", price.total)
            
            val (result, refund) = stateMachine.cancel("BK001")
            
            assertTrue(result is TransitionResult.Success)
            assertNotNull(refund)
            assertTrue(refund!!.penalty > 0 || refund.refundAmount < price.total)
        }
        
        @Test
        fun `flight cancelled reason gives full refund`() {
            val futureFlight = createTestFlight(departure = LocalDateTime.now().plusDays(1))
            val price = PriceBreakdown(200.0, 30.0, 15.0)
            stateMachine.createBooking("BK001", passenger, listOf(futureFlight), price)
            stateMachine.selectSeats("BK001", listOf(futureFlight.seats.first()))
            stateMachine.proceedToPayment("BK001")
            stateMachine.confirmPayment("BK001", price.total)
            
            val (result, refund) = stateMachine.cancel("BK001", CancellationReason.FLIGHT_CANCELLED)
            
            assertTrue(result is TransitionResult.Success)
            assertNotNull(refund)
            assertEquals(price.total, refund!!.refundAmount, 0.01)
            assertEquals(0.0, refund.penalty, 0.01)
        }
        
        @Test
        fun `cannot cancel completed booking`() {
            val flight = createTestFlight()
            val price = PriceBreakdown(200.0, 30.0, 15.0)
            stateMachine.createBooking("BK001", passenger, listOf(flight), price)
            stateMachine.selectSeats("BK001", listOf(flight.seats.first()))
            stateMachine.proceedToPayment("BK001")
            stateMachine.confirmPayment("BK001", price.total)
            stateMachine.transition("BK001", BookingStatus.CHECKED_IN)
            stateMachine.transition("BK001", BookingStatus.BOARDED)
            stateMachine.transition("BK001", BookingStatus.COMPLETED)
            
            val (result, _) = stateMachine.cancel("BK001")
            
            assertTrue(result is TransitionResult.InvalidTransition)
        }
        
        @Test
        fun `cannot cancel already cancelled booking`() {
            val flight = createTestFlight()
            stateMachine.createBooking("BK001", passenger, listOf(flight), PriceBreakdown(200.0, 30.0, 15.0))
            stateMachine.cancel("BK001")
            
            val (result, _) = stateMachine.cancel("BK001")
            
            assertTrue(result is TransitionResult.InvalidTransition)
        }
    }
    
    @Nested
    inner class MultiLegItineraryBuildingTest {
        
        private lateinit var passenger: Passenger
        
        @BeforeEach
        fun setup() {
            passenger = createTestPassenger()
        }
        
        @Test
        fun `builds simple one-way itinerary`() {
            val flight = createTestFlight()
            
            val itinerary = ItineraryBuilder.oneWay(passenger, flight).build()
            
            assertEquals(1, itinerary.bookings.size)
            assertEquals(1, itinerary.allFlights.size)
        }
        
        @Test
        fun `builds round trip itinerary`() {
            val (jfk, lax) = createTestAirports()
            val outbound = createTestFlight(origin = jfk, destination = lax)
            val returnFlight = createTestFlight(
                origin = lax,
                destination = jfk,
                departure = LocalDateTime.now().plusDays(14)
            )
            
            val itinerary = ItineraryBuilder.roundTrip(passenger, outbound, returnFlight).build()
            
            assertEquals(1, itinerary.bookings.size)
            assertEquals(2, itinerary.allFlights.size)
            assertTrue(itinerary.isRoundTrip)
        }
        
        @Test
        fun `builds multi-city itinerary with connections`() {
            val jfk = Airport("JFK", "JFK", "New York")
            val ord = Airport("ORD", "O'Hare", "Chicago")
            val lax = Airport("LAX", "LAX", "Los Angeles")
            
            val leg1 = createTestFlight(
                origin = jfk,
                destination = ord,
                departure = LocalDateTime.now().plusDays(7)
            )
            val leg2 = createTestFlight(
                origin = ord,
                destination = lax,
                departure = LocalDateTime.now().plusDays(7).plusHours(8)
            )
            
            val itinerary = ItineraryBuilder.multiCity(passenger, leg1, leg2).build()
            
            assertEquals(1, itinerary.bookings.size)
            assertEquals(2, itinerary.allFlights.size)
            assertEquals(1, itinerary.connections.size)
        }
        
        @Test
        fun `validates connection destinations match origins`() {
            val jfk = Airport("JFK", "JFK", "New York")
            val ord = Airport("ORD", "O'Hare", "Chicago")
            val lax = Airport("LAX", "LAX", "Los Angeles")
            
            val leg1 = createTestFlight(origin = jfk, destination = lax)
            val leg2 = createTestFlight(origin = ord, destination = lax)
            
            val builder = ItineraryBuilder()
                .addPassenger(passenger)
                .addFlight(leg1)
                .addFlight(leg2)
            
            val validation = builder.validate()
            
            assertTrue(validation is ValidationResult.Invalid)
            assertTrue((validation as ValidationResult.Invalid).errors.any { 
                it.contains("does not match") 
            })
        }
        
        @Test
        fun `validates minimum layover time`() {
            val jfk = Airport("JFK", "JFK", "New York")
            val ord = Airport("ORD", "O'Hare", "Chicago")
            val lax = Airport("LAX", "LAX", "Los Angeles")
            
            val departure1 = LocalDateTime.now().plusDays(7)
            val leg1 = createTestFlight(
                origin = jfk,
                destination = ord,
                departure = departure1
            )
            val leg2 = createTestFlight(
                origin = ord,
                destination = lax,
                departure = departure1.plusHours(5).plusMinutes(30)
            )
            
            val builder = ItineraryBuilder()
                .addPassenger(passenger)
                .addFlight(leg1)
                .addFlight(leg2)
            
            val validation = builder.validate()
            
            assertTrue(validation is ValidationResult.Invalid)
            assertTrue((validation as ValidationResult.Invalid).errors.any { 
                it.contains("too short") 
            })
        }
        
        @Test
        fun `validates at least one passenger required`() {
            val flight = createTestFlight()
            
            val builder = ItineraryBuilder()
                .addFlight(flight)
            
            val validation = builder.validate()
            
            assertTrue(validation is ValidationResult.Invalid)
            assertTrue((validation as ValidationResult.Invalid).errors.any { 
                it.contains("passenger") 
            })
        }
        
        @Test
        fun `build throws for invalid itinerary`() {
            val flight = createTestFlight()
            
            val builder = ItineraryBuilder()
                .addFlight(flight)
            
            assertThrows<IllegalStateException> {
                builder.build()
            }
        }
        
        @Test
        fun `buildOrNull returns null for invalid itinerary`() {
            val flight = createTestFlight()
            
            val result = ItineraryBuilder()
                .addFlight(flight)
                .buildOrNull()
            
            assertNull(result)
        }
    }
    
    @Nested
    inner class PriceCalculationTest {
        
        private lateinit var passenger: Passenger
        private lateinit var flight: Flight
        
        @BeforeEach
        fun setup() {
            passenger = createTestPassenger()
            flight = createTestFlight()
        }
        
        @Test
        fun `price includes base fare, taxes, and fees`() {
            val itinerary = ItineraryBuilder.oneWay(passenger, flight).build()
            
            val price = itinerary.bookings.first().priceBreakdown
            
            assertTrue(price.baseFare > 0)
            assertTrue(price.taxes > 0)
            assertTrue(price.fees > 0)
            assertEquals(price.baseFare + price.taxes + price.fees, price.total, 0.01)
        }
        
        @Test
        fun `business class has higher fare than economy`() {
            val economyItinerary = ItineraryBuilder.oneWay(passenger, flight)
                .withClass(SeatClass.ECONOMY)
                .build()
            
            val businessItinerary = ItineraryBuilder.oneWay(passenger, flight)
                .withClass(SeatClass.BUSINESS)
                .build()
            
            assertTrue(businessItinerary.totalPrice > economyItinerary.totalPrice)
        }
        
        @Test
        fun `insurance adds to total price`() {
            val withoutInsurance = ItineraryBuilder.oneWay(passenger, flight).build()
            val withInsurance = ItineraryBuilder.oneWay(passenger, flight)
                .withInsurance()
                .build()
            
            assertTrue(withInsurance.totalPrice > withoutInsurance.totalPrice)
            assertTrue(withInsurance.bookings.first().priceBreakdown.insurance > 0)
        }
        
        @Test
        fun `discount reduces total price`() {
            val withoutDiscount = ItineraryBuilder.oneWay(passenger, flight).build()
            val withDiscount = ItineraryBuilder.oneWay(passenger, flight)
                .withDiscount("SUMMER20", 20.0)
                .build()
            
            assertTrue(withDiscount.totalPrice < withoutDiscount.totalPrice)
            assertTrue(withDiscount.bookings.first().priceBreakdown.discount > 0)
        }
        
        @Test
        fun `discount is capped at 50 percent`() {
            val itinerary = ItineraryBuilder.oneWay(passenger, flight)
                .withDiscount("MEGA", 100.0)
                .build()
            
            val price = itinerary.bookings.first().priceBreakdown
            val maxDiscount = (price.baseFare + price.taxes + price.fees) * 0.5
            
            assertTrue(price.discount <= maxDiscount + 0.01)
        }
        
        @Test
        fun `multi-leg trip sums prices correctly`() {
            val jfk = Airport("JFK", "JFK", "New York")
            val ord = Airport("ORD", "O'Hare", "Chicago")
            val lax = Airport("LAX", "LAX", "Los Angeles")
            
            val leg1 = createTestFlight(
                origin = jfk,
                destination = ord,
                departure = LocalDateTime.now().plusDays(7)
            )
            val leg2 = createTestFlight(
                origin = ord,
                destination = lax,
                departure = LocalDateTime.now().plusDays(7).plusHours(8)
            )
            
            val itinerary = ItineraryBuilder.multiCity(passenger, leg1, leg2).build()
            
            assertTrue(itinerary.totalPrice > 0)
        }
        
        @Test
        fun `group booking creates separate bookings per passenger`() {
            val passenger2 = Passenger("P002", "Jane Doe", "CD789012")
            val director = ItineraryDirector()
            
            val itinerary = director.createGroupTrip(
                listOf(passenger, passenger2),
                flight
            )
            
            assertEquals(2, itinerary.bookings.size)
            assertEquals(2, itinerary.passengers.size)
        }
        
        @Test
        fun `price breakdown addition works correctly`() {
            val price1 = PriceBreakdown(100.0, 15.0, 10.0, 20.0, 5.0, 10.0)
            val price2 = PriceBreakdown(200.0, 30.0, 20.0, 40.0, 10.0, 20.0)
            
            val combined = price1 + price2
            
            assertEquals(300.0, combined.baseFare)
            assertEquals(45.0, combined.taxes)
            assertEquals(30.0, combined.fees)
            assertEquals(60.0, combined.seatUpgrade)
            assertEquals(15.0, combined.insurance)
            assertEquals(30.0, combined.discount)
        }
    }
    
    @Nested
    inner class ItineraryDirectorTest {
        
        private lateinit var director: ItineraryDirector
        private lateinit var passenger: Passenger
        private lateinit var flight: Flight
        
        @BeforeEach
        fun setup() {
            director = ItineraryDirector()
            passenger = createTestPassenger()
            flight = createTestFlight()
        }
        
        @Test
        fun `creates one-way trip`() {
            val itinerary = director.createOneWayTrip(passenger, flight)
            
            assertEquals(1, itinerary.allFlights.size)
            assertFalse(itinerary.isRoundTrip)
        }
        
        @Test
        fun `creates round trip`() {
            val (jfk, lax) = createTestAirports()
            val outbound = createTestFlight(origin = jfk, destination = lax)
            val returnFlight = createTestFlight(
                origin = lax,
                destination = jfk,
                departure = LocalDateTime.now().plusDays(14)
            )
            
            val itinerary = director.createRoundTrip(passenger, outbound, returnFlight)
            
            assertEquals(2, itinerary.allFlights.size)
            assertTrue(itinerary.isRoundTrip)
        }
        
        @Test
        fun `creates multi-city trip`() {
            val jfk = Airport("JFK", "JFK", "New York")
            val ord = Airport("ORD", "O'Hare", "Chicago")
            val lax = Airport("LAX", "LAX", "Los Angeles")
            
            val flights = listOf(
                createTestFlight(origin = jfk, destination = ord, departure = LocalDateTime.now().plusDays(7)),
                createTestFlight(origin = ord, destination = lax, departure = LocalDateTime.now().plusDays(7).plusHours(8))
            )
            
            val itinerary = director.createMultiCityTrip(passenger, flights)
            
            assertEquals(2, itinerary.allFlights.size)
            assertFalse(itinerary.isRoundTrip)
        }
        
        @Test
        fun `creates group trip with discount`() {
            val passengers = listOf(
                passenger,
                Passenger("P002", "Jane Doe", "CD789012"),
                Passenger("P003", "Bob Smith", "EF345678")
            )
            
            val itinerary = director.createGroupTrip(
                passengers,
                flight,
                discountCode = "GROUP10",
                discountPercentage = 10.0
            )
            
            assertEquals(3, itinerary.bookings.size)
            assertTrue(itinerary.bookings.all { it.priceBreakdown.discount > 0 })
        }
    }
    
    @Nested
    inner class FlightExtensionsTest {
        
        @Test
        fun `toItinerary extension creates builder from flight list`() {
            val passenger = createTestPassenger()
            val flight = createTestFlight()
            
            val builder = listOf(flight).toItinerary(passenger)
            val itinerary = builder.build()
            
            assertEquals(1, itinerary.allFlights.size)
        }
        
        @Test
        fun `connectingTo extension creates flight list`() {
            val jfk = Airport("JFK", "JFK", "New York")
            val ord = Airport("ORD", "O'Hare", "Chicago")
            val lax = Airport("LAX", "LAX", "Los Angeles")
            
            val leg1 = createTestFlight(origin = jfk, destination = ord)
            val leg2 = createTestFlight(origin = ord, destination = lax)
            
            val flights = leg1.connectingTo(leg2)
            
            assertEquals(2, flights.size)
        }
        
        @Test
        fun `hasValidConnections validates flight list`() {
            val jfk = Airport("JFK", "JFK", "New York")
            val ord = Airport("ORD", "O'Hare", "Chicago")
            val lax = Airport("LAX", "LAX", "Los Angeles")
            
            val departure = LocalDateTime.now().plusDays(7)
            val validFlights = listOf(
                createTestFlight(origin = jfk, destination = ord, departure = departure),
                createTestFlight(origin = ord, destination = lax, departure = departure.plusHours(8))
            )
            
            val invalidFlights = listOf(
                createTestFlight(origin = jfk, destination = lax, departure = departure),
                createTestFlight(origin = ord, destination = lax, departure = departure.plusHours(8))
            )
            
            assertTrue(validFlights.hasValidConnections())
            assertFalse(invalidFlights.hasValidConnections())
        }
    }
    
    @Nested
    inner class BookingObserverTest {
        
        @Test
        fun `observer notified on booking created`() {
            var createdBooking: Booking? = null
            val observer = object : BookingObserver {
                override fun onBookingCreated(booking: Booking) { createdBooking = booking }
                override fun onStatusChanged(booking: Booking, oldStatus: BookingStatus, newStatus: BookingStatus) {}
                override fun onPaymentReceived(booking: Booking, amount: Double) {}
                override fun onSeatAssigned(booking: Booking, seat: Seat) {}
                override fun onBookingCancelled(booking: Booking, refund: RefundResult) {}
                override fun onError(booking: Booking?, message: String) {}
            }
            
            val stateMachine = BookingStateMachine()
            stateMachine.addObserver(observer)
            
            val booking = stateMachine.createBooking(
                "BK001",
                createTestPassenger(),
                listOf(createTestFlight()),
                PriceBreakdown(200.0, 30.0, 15.0)
            )
            
            assertEquals(booking.id, createdBooking?.id)
        }
        
        @Test
        fun `observer notified on status change`() {
            var oldStatus: BookingStatus? = null
            var newStatus: BookingStatus? = null
            val observer = object : BookingObserver {
                override fun onBookingCreated(booking: Booking) {}
                override fun onStatusChanged(booking: Booking, old: BookingStatus, new: BookingStatus) {
                    oldStatus = old
                    newStatus = new
                }
                override fun onPaymentReceived(booking: Booking, amount: Double) {}
                override fun onSeatAssigned(booking: Booking, seat: Seat) {}
                override fun onBookingCancelled(booking: Booking, refund: RefundResult) {}
                override fun onError(booking: Booking?, message: String) {}
            }
            
            val stateMachine = BookingStateMachine()
            stateMachine.addObserver(observer)
            
            val flight = createTestFlight()
            stateMachine.createBooking("BK001", createTestPassenger(), listOf(flight), PriceBreakdown(200.0, 30.0, 15.0))
            stateMachine.selectSeats("BK001", listOf(flight.seats.first()))
            
            assertEquals(BookingStatus.SEARCHING, oldStatus)
            assertEquals(BookingStatus.SELECTED, newStatus)
        }
        
        @Test
        fun `observer notified on cancellation with refund`() {
            var refundResult: RefundResult? = null
            val observer = object : BookingObserver {
                override fun onBookingCreated(booking: Booking) {}
                override fun onStatusChanged(booking: Booking, old: BookingStatus, new: BookingStatus) {}
                override fun onPaymentReceived(booking: Booking, amount: Double) {}
                override fun onSeatAssigned(booking: Booking, seat: Seat) {}
                override fun onBookingCancelled(booking: Booking, refund: RefundResult) { refundResult = refund }
                override fun onError(booking: Booking?, message: String) {}
            }
            
            val stateMachine = BookingStateMachine()
            stateMachine.addObserver(observer)
            
            val flight = createTestFlight()
            val price = PriceBreakdown(200.0, 30.0, 15.0)
            stateMachine.createBooking("BK001", createTestPassenger(), listOf(flight), price)
            stateMachine.selectSeats("BK001", listOf(flight.seats.first()))
            stateMachine.cancel("BK001")
            
            assertNotNull(refundResult)
            assertEquals(price.total, refundResult!!.originalAmount, 0.01)
        }
    }
    
    @Nested
    inner class FlightBookingManagerIntegrationTest {
        
        @Test
        fun `complete booking flow from search to confirmation`() {
            val manager = FlightBookingManager()
            val passenger = createTestPassenger()
            val flight = createTestFlight()
            val price = PriceBreakdown(200.0, 30.0, 15.0)
            
            val booking = manager.searchAndCreateBooking(passenger, listOf(flight), price)
            assertEquals(BookingStatus.SEARCHING, booking.status)
            
            val seatResult = manager.selectSeats(booking.id, listOf(flight.seats.first()))
            assertTrue(seatResult is BookingResult.Success)
            assertEquals(BookingStatus.SELECTED, (seatResult as BookingResult.Success).booking.status)
            
            val paymentPendingResult = manager.proceedToPayment(booking.id)
            assertTrue(paymentPendingResult is BookingResult.Success)
            assertEquals(BookingStatus.PAYMENT_PENDING, (paymentPendingResult as BookingResult.Success).booking.status)
            
            val confirmResult = manager.confirmPayment(booking.id, price.total)
            assertTrue(confirmResult is BookingResult.Success)
            assertEquals(BookingStatus.CONFIRMED, (confirmResult as BookingResult.Success).booking.status)
        }
        
        @Test
        fun `booking can be cancelled at any active state`() {
            val manager = FlightBookingManager()
            val passenger = createTestPassenger()
            val flight = createTestFlight()
            val price = PriceBreakdown(200.0, 30.0, 15.0)
            
            val booking = manager.searchAndCreateBooking(passenger, listOf(flight), price)
            manager.selectSeats(booking.id, listOf(flight.seats.first()))
            
            val cancelResult = manager.cancelBooking(booking.id)
            
            assertTrue(cancelResult is BookingResult.Cancelled)
        }
    }
}
