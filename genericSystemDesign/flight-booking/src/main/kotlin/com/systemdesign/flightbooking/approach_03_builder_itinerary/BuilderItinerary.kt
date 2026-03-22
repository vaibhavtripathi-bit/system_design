package com.systemdesign.flightbooking.approach_03_builder_itinerary

import com.systemdesign.flightbooking.common.*
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID

/**
 * Approach 3: Builder Pattern for Itinerary Construction
 * 
 * Complex multi-leg trip itineraries are constructed step by step
 * using the builder pattern, ensuring valid configurations.
 * 
 * Pattern: Builder
 * 
 * Trade-offs:
 * + Fluent API for constructing complex objects
 * + Validation at build time prevents invalid states
 * + Easy to create variations (round trip, multi-city)
 * + Self-documenting code
 * - More code than simple constructors
 * - Mutable builder state requires careful handling
 * 
 * When to use:
 * - When objects have many optional parameters
 * - When object construction has complex validation
 * - When you want a fluent, readable API
 * 
 * Extensibility:
 * - New trip type: Add new convenience method
 * - New validation: Add to build() method
 * - New price component: Add to PriceCalculator
 */

/** Validation result for itinerary construction */
sealed class ValidationResult {
    data object Valid : ValidationResult()
    data class Invalid(val errors: List<String>) : ValidationResult()
}

/** Price calculator for itinerary */
interface PriceCalculator {
    fun calculateBaseFare(flights: List<Flight>, seatClass: SeatClass): Double
    fun calculateTaxes(baseFare: Double, flights: List<Flight>): Double
    fun calculateFees(flights: List<Flight>, passengers: Int): Double
    fun calculateSeatUpgrade(seats: List<Seat>, baseClass: SeatClass): Double
}

/** Default price calculator implementation */
class DefaultPriceCalculator : PriceCalculator {
    
    override fun calculateBaseFare(flights: List<Flight>, seatClass: SeatClass): Double {
        val baseFare = flights.sumOf { flight ->
            val distanceFactor = Duration.between(flight.departure, flight.arrival).toHours() * 50.0
            distanceFactor * seatClass.multiplier
        }
        return baseFare
    }
    
    override fun calculateTaxes(baseFare: Double, flights: List<Flight>): Double {
        val taxRate = 0.15
        val airportTax = flights.size * 25.0
        return (baseFare * taxRate) + airportTax
    }
    
    override fun calculateFees(flights: List<Flight>, passengers: Int): Double {
        val bookingFee = 15.0
        val fuelSurcharge = flights.size * 30.0
        val securityFee = passengers * 5.60 * flights.size
        return bookingFee + fuelSurcharge + securityFee
    }
    
    override fun calculateSeatUpgrade(seats: List<Seat>, baseClass: SeatClass): Double {
        return seats.sumOf { seat ->
            if (seat.seatClass != baseClass) {
                seat.price * (seat.seatClass.multiplier - baseClass.multiplier)
            } else {
                0.0
            }
        }
    }
}

/** Builder for constructing complex itineraries */
class ItineraryBuilder(
    private val priceCalculator: PriceCalculator = DefaultPriceCalculator()
) {
    private val legs = mutableListOf<FlightLegBuilder>()
    private val passengers = mutableListOf<Passenger>()
    private var preferredClass = SeatClass.ECONOMY
    private var includeInsurance = false
    private var insuranceRate = 0.05
    private var discountCode: String? = null
    private var discountPercentage = 0.0
    
    fun addPassenger(passenger: Passenger): ItineraryBuilder {
        passengers.add(passenger)
        return this
    }
    
    fun addPassengers(vararg passengerList: Passenger): ItineraryBuilder {
        passengers.addAll(passengerList)
        return this
    }
    
    fun withClass(seatClass: SeatClass): ItineraryBuilder {
        preferredClass = seatClass
        return this
    }
    
    fun addFlight(flight: Flight): ItineraryBuilder {
        legs.add(FlightLegBuilder(flight, legs.size))
        return this
    }
    
    fun addFlights(vararg flights: Flight): ItineraryBuilder {
        flights.forEach { addFlight(it) }
        return this
    }
    
    fun withSeat(legIndex: Int, seat: Seat): ItineraryBuilder {
        if (legIndex < legs.size) {
            legs[legIndex].seat = seat
        }
        return this
    }
    
    fun withSeats(seats: Map<Int, Seat>): ItineraryBuilder {
        seats.forEach { (index, seat) -> withSeat(index, seat) }
        return this
    }
    
    fun withInsurance(): ItineraryBuilder {
        includeInsurance = true
        return this
    }
    
    fun withInsurance(rate: Double): ItineraryBuilder {
        includeInsurance = true
        insuranceRate = rate
        return this
    }
    
    fun withDiscount(code: String, percentage: Double): ItineraryBuilder {
        discountCode = code
        discountPercentage = percentage.coerceIn(0.0, 50.0)
        return this
    }
    
    fun validate(): ValidationResult {
        val errors = mutableListOf<String>()
        
        if (passengers.isEmpty()) {
            errors.add("At least one passenger is required")
        }
        
        if (legs.isEmpty()) {
            errors.add("At least one flight is required")
        }
        
        for (i in 0 until legs.size - 1) {
            val currentLeg = legs[i]
            val nextLeg = legs[i + 1]
            
            if (currentLeg.flight.destination.code != nextLeg.flight.origin.code) {
                errors.add("Leg ${i + 1} destination (${currentLeg.flight.destination.code}) " +
                    "does not match leg ${i + 2} origin (${nextLeg.flight.origin.code})")
            }
            
            val layoverDuration = Duration.between(
                currentLeg.flight.arrival,
                nextLeg.flight.departure
            )
            
            if (layoverDuration.isNegative) {
                errors.add("Leg ${i + 2} departs before leg ${i + 1} arrives")
            } else if (layoverDuration.toMinutes() < 45) {
                errors.add("Layover between leg ${i + 1} and ${i + 2} is too short " +
                    "(${layoverDuration.toMinutes()} minutes, minimum 45 required)")
            }
        }
        
        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }
    
    fun build(): Itinerary {
        val validation = validate()
        if (validation is ValidationResult.Invalid) {
            throw IllegalStateException("Invalid itinerary: ${validation.errors.joinToString("; ")}")
        }
        
        val bookings = passengers.map { passenger ->
            createBookingForPassenger(passenger)
        }
        
        val connections = buildConnections()
        
        return Itinerary(
            id = UUID.randomUUID().toString(),
            bookings = bookings,
            connections = connections
        )
    }
    
    fun buildOrNull(): Itinerary? {
        return try {
            build()
        } catch (e: IllegalStateException) {
            null
        }
    }
    
    private fun createBookingForPassenger(passenger: Passenger): Booking {
        val flights = legs.map { it.flight }
        val seats = legs.mapNotNull { it.seat }
        val priceBreakdown = calculatePrice(flights, seats)
        
        return Booking(
            id = UUID.randomUUID().toString(),
            passenger = passenger,
            flights = flights,
            seats = seats,
            status = BookingStatus.SEARCHING,
            priceBreakdown = priceBreakdown
        )
    }
    
    private fun calculatePrice(flights: List<Flight>, seats: List<Seat>): PriceBreakdown {
        val baseFare = priceCalculator.calculateBaseFare(flights, preferredClass)
        val taxes = priceCalculator.calculateTaxes(baseFare, flights)
        val fees = priceCalculator.calculateFees(flights, passengers.size)
        val seatUpgrade = priceCalculator.calculateSeatUpgrade(seats, preferredClass)
        
        val subtotal = baseFare + taxes + fees + seatUpgrade
        val insurance = if (includeInsurance) subtotal * insuranceRate else 0.0
        val discount = if (discountCode != null) subtotal * (discountPercentage / 100) else 0.0
        
        return PriceBreakdown(
            baseFare = baseFare,
            taxes = taxes,
            fees = fees,
            seatUpgrade = seatUpgrade,
            insurance = insurance,
            discount = discount
        )
    }
    
    private fun buildConnections(): List<Connection> {
        val connections = mutableListOf<Connection>()
        
        for (i in 0 until legs.size - 1) {
            val arrivalFlight = legs[i].flight
            val departureFlight = legs[i + 1].flight
            val layoverDuration = Duration.between(
                arrivalFlight.arrival,
                departureFlight.departure
            )
            
            connections.add(Connection(arrivalFlight, departureFlight, layoverDuration))
        }
        
        return connections
    }
    
    private data class FlightLegBuilder(
        val flight: Flight,
        val order: Int,
        var seat: Seat? = null
    )
    
    companion object {
        fun oneWay(
            passenger: Passenger,
            flight: Flight,
            seat: Seat? = null
        ): ItineraryBuilder {
            return ItineraryBuilder()
                .addPassenger(passenger)
                .addFlight(flight)
                .apply { seat?.let { withSeat(0, it) } }
        }
        
        fun roundTrip(
            passenger: Passenger,
            outboundFlight: Flight,
            returnFlight: Flight
        ): ItineraryBuilder {
            return ItineraryBuilder()
                .addPassenger(passenger)
                .addFlight(outboundFlight)
                .addFlight(returnFlight)
        }
        
        fun multiCity(
            passenger: Passenger,
            vararg flights: Flight
        ): ItineraryBuilder {
            return ItineraryBuilder()
                .addPassenger(passenger)
                .addFlights(*flights)
        }
    }
}

/** Director for common itinerary patterns */
class ItineraryDirector(
    private val priceCalculator: PriceCalculator = DefaultPriceCalculator()
) {
    fun createOneWayTrip(
        passenger: Passenger,
        flight: Flight,
        seatClass: SeatClass = SeatClass.ECONOMY,
        includeInsurance: Boolean = false
    ): Itinerary {
        val builder = ItineraryBuilder(priceCalculator)
            .addPassenger(passenger)
            .addFlight(flight)
            .withClass(seatClass)
        
        if (includeInsurance) {
            builder.withInsurance()
        }
        
        return builder.build()
    }
    
    fun createRoundTrip(
        passenger: Passenger,
        outboundFlight: Flight,
        returnFlight: Flight,
        seatClass: SeatClass = SeatClass.ECONOMY,
        includeInsurance: Boolean = false
    ): Itinerary {
        val builder = ItineraryBuilder(priceCalculator)
            .addPassenger(passenger)
            .addFlight(outboundFlight)
            .addFlight(returnFlight)
            .withClass(seatClass)
        
        if (includeInsurance) {
            builder.withInsurance()
        }
        
        return builder.build()
    }
    
    fun createMultiCityTrip(
        passenger: Passenger,
        flights: List<Flight>,
        seatClass: SeatClass = SeatClass.ECONOMY,
        includeInsurance: Boolean = false
    ): Itinerary {
        val builder = ItineraryBuilder(priceCalculator)
            .addPassenger(passenger)
            .withClass(seatClass)
        
        flights.forEach { builder.addFlight(it) }
        
        if (includeInsurance) {
            builder.withInsurance()
        }
        
        return builder.build()
    }
    
    fun createGroupTrip(
        passengers: List<Passenger>,
        flight: Flight,
        seatClass: SeatClass = SeatClass.ECONOMY,
        discountCode: String? = null,
        discountPercentage: Double = 0.0
    ): Itinerary {
        val builder = ItineraryBuilder(priceCalculator)
            .addPassengers(*passengers.toTypedArray())
            .addFlight(flight)
            .withClass(seatClass)
        
        if (discountCode != null && discountPercentage > 0) {
            builder.withDiscount(discountCode, discountPercentage)
        }
        
        return builder.build()
    }
}

/** Fluent extension for building itineraries from search results */
fun List<Flight>.toItinerary(passenger: Passenger): ItineraryBuilder {
    val builder = ItineraryBuilder()
        .addPassenger(passenger)
    
    this.forEach { builder.addFlight(it) }
    
    return builder
}

/** Extension to add connecting flight */
fun Flight.connectingTo(nextFlight: Flight): List<Flight> = listOf(this, nextFlight)

/** Extension to validate connection */
fun List<Flight>.hasValidConnections(): Boolean {
    if (size < 2) return true
    
    for (i in 0 until size - 1) {
        val current = this[i]
        val next = this[i + 1]
        
        if (current.destination.code != next.origin.code) return false
        
        val layover = Duration.between(current.arrival, next.departure)
        if (layover.isNegative || layover.toMinutes() < 45) return false
    }
    
    return true
}
