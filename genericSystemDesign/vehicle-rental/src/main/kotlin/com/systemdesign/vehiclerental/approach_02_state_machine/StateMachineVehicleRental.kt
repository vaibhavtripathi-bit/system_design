package com.systemdesign.vehiclerental.approach_02_state_machine

import com.systemdesign.vehiclerental.common.*
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Approach 2: State Machine Pattern for Rental Lifecycle
 * 
 * The rental is modeled as an explicit finite state machine.
 * Each state has specific valid operations and transitions.
 * 
 * Pattern: State Machine
 * 
 * Trade-offs:
 * + Clear state transitions prevent invalid operations
 * + Easy to reason about rental lifecycle
 * + State-specific validation and business rules
 * - More boilerplate for state management
 * - State explosion if too many orthogonal concerns
 * 
 * When to use:
 * - When system has clear discrete states
 * - When operations are only valid in certain states
 * - When audit/logging of state transitions is important
 * 
 * Extensibility:
 * - New state: Add to enum and update transition table
 * - New operation: Add handler in appropriate state
 */

/** State transition definition */
data class StateTransition(
    val from: RentalStatus,
    val to: RentalStatus,
    val action: String,
    val condition: ((Rental) -> Boolean)? = null
)

/** State machine for rental lifecycle */
class RentalStateMachine {
    
    private val validTransitions = mapOf(
        RentalStatus.AVAILABLE to setOf(
            RentalStatus.RESERVED,
            RentalStatus.UNDER_MAINTENANCE,
            RentalStatus.RETIRED
        ),
        RentalStatus.RESERVED to setOf(
            RentalStatus.PICKED_UP,
            RentalStatus.AVAILABLE  // Cancel reservation
        ),
        RentalStatus.PICKED_UP to setOf(
            RentalStatus.RETURNED
        ),
        RentalStatus.RETURNED to setOf(
            RentalStatus.AVAILABLE,
            RentalStatus.UNDER_MAINTENANCE
        ),
        RentalStatus.UNDER_MAINTENANCE to setOf(
            RentalStatus.AVAILABLE,
            RentalStatus.RETIRED
        ),
        RentalStatus.RETIRED to emptySet<RentalStatus>()
    )
    
    fun canTransition(from: RentalStatus, to: RentalStatus): Boolean {
        return validTransitions[from]?.contains(to) == true
    }
    
    fun getValidTransitions(from: RentalStatus): Set<RentalStatus> {
        return validTransitions[from] ?: emptySet()
    }
    
    fun validateTransition(from: RentalStatus, to: RentalStatus): TransitionResult {
        return if (canTransition(from, to)) {
            TransitionResult.Valid
        } else {
            TransitionResult.Invalid("Cannot transition from $from to $to")
        }
    }
}

sealed class TransitionResult {
    data object Valid : TransitionResult()
    data class Invalid(val reason: String) : TransitionResult()
}

/** Rental manager with state machine control */
class StateMachineRentalManager(
    private val stateMachine: RentalStateMachine = RentalStateMachine(),
    private val pricingEngine: SimplePricingEngine = SimplePricingEngine()
) {
    private val rentals = mutableMapOf<String, Rental>()
    private val vehicles = mutableMapOf<String, Vehicle>()
    private val locations = mutableMapOf<String, Location>()
    private val transitionHistory = mutableListOf<TransitionRecord>()
    private val observers = CopyOnWriteArrayList<RentalObserver>()
    
    private var rentalIdCounter = 1
    
    fun addVehicle(vehicle: Vehicle) {
        vehicles[vehicle.id] = vehicle
    }
    
    fun addLocation(location: Location) {
        locations[location.id] = location
    }
    
    fun getVehicle(vehicleId: String): Vehicle? = vehicles[vehicleId]
    fun getRental(rentalId: String): Rental? = rentals[rentalId]
    fun getLocation(locationId: String): Location? = locations[locationId]
    
    fun reserve(
        vehicleId: String,
        customer: Customer,
        period: RentalPeriod,
        addons: List<Addon> = emptyList(),
        pickupLocationId: String? = null,
        dropoffLocationId: String? = null
    ): RentalResult {
        val vehicle = vehicles[vehicleId] 
            ?: return RentalResult.VehicleUnavailable(vehicleId, "Vehicle not found")
        
        val transitionResult = stateMachine.validateTransition(
            vehicle.status, 
            RentalStatus.RESERVED
        )
        
        if (transitionResult is TransitionResult.Invalid) {
            return RentalResult.InvalidTransition(vehicle.status, RentalStatus.RESERVED)
        }
        
        if (period.start.isAfter(period.end)) {
            return RentalResult.InvalidPeriod("Start date must be before end date")
        }
        
        val priceBreakdown = pricingEngine.calculatePrice(vehicle, period, addons)
        
        val rental = Rental(
            id = "RNT-${rentalIdCounter++}",
            vehicle = vehicle,
            customer = customer,
            period = period,
            addons = addons,
            status = RentalStatus.RESERVED,
            pickupLocation = pickupLocationId?.let { locations[it] },
            dropoffLocation = dropoffLocationId?.let { locations[it] },
            totalPrice = priceBreakdown.total()
        )
        
        vehicle.status = RentalStatus.RESERVED
        rentals[rental.id] = rental
        
        recordTransition(rental.id, RentalStatus.AVAILABLE, RentalStatus.RESERVED, "reserve")
        notifyRentalCreated(rental)
        
        return RentalResult.Success(rental, priceBreakdown)
    }
    
    fun pickup(rentalId: String): RentalResult {
        val rental = rentals[rentalId] ?: return RentalResult.NotFound
        
        val transitionResult = stateMachine.validateTransition(
            rental.status,
            RentalStatus.PICKED_UP
        )
        
        if (transitionResult is TransitionResult.Invalid) {
            return RentalResult.InvalidTransition(rental.status, RentalStatus.PICKED_UP)
        }
        
        val previousStatus = rental.status
        rental.vehicle.status = RentalStatus.PICKED_UP
        val updatedRental = rental.copy(status = RentalStatus.PICKED_UP)
        rentals[rentalId] = updatedRental
        
        recordTransition(rentalId, previousStatus, RentalStatus.PICKED_UP, "pickup")
        notifyVehiclePickedUp(updatedRental)
        
        val priceBreakdown = pricingEngine.calculatePrice(
            rental.vehicle, rental.period, rental.addons
        )
        
        return RentalResult.Success(updatedRental, priceBreakdown)
    }
    
    fun returnVehicle(
        rentalId: String,
        actualReturnTime: LocalDateTime = LocalDateTime.now(),
        damageAssessment: DamageAssessment? = null
    ): RentalResult {
        val rental = rentals[rentalId] ?: return RentalResult.NotFound
        
        val transitionResult = stateMachine.validateTransition(
            rental.status,
            RentalStatus.RETURNED
        )
        
        if (transitionResult is TransitionResult.Invalid) {
            return RentalResult.InvalidTransition(rental.status, RentalStatus.RETURNED)
        }
        
        val previousStatus = rental.status
        
        var priceBreakdown = pricingEngine.calculatePrice(
            rental.vehicle, rental.period, rental.addons
        )
        
        val hoursLate = calculateLateHours(rental.period.end, actualReturnTime)
        if (hoursLate > GRACE_PERIOD_HOURS) {
            val lateFee = pricingEngine.calculateLateFee(rental.vehicle, hoursLate)
            priceBreakdown = priceBreakdown.copy(lateFee = lateFee)
            notifyLateReturn(rental, hoursLate)
        }
        
        if (damageAssessment?.hasDamage == true) {
            priceBreakdown = priceBreakdown.copy(damageFee = damageAssessment.repairCost)
            notifyDamageReported(rental, damageAssessment)
            
            rental.vehicle.status = RentalStatus.UNDER_MAINTENANCE
            notifyMaintenanceRequired(rental.vehicle)
        } else {
            rental.vehicle.status = RentalStatus.RETURNED
        }
        
        val updatedRental = rental.copy(
            status = RentalStatus.RETURNED,
            actualReturnTime = actualReturnTime,
            damageAssessment = damageAssessment,
            totalPrice = priceBreakdown.total()
        )
        rentals[rentalId] = updatedRental
        
        recordTransition(rentalId, previousStatus, RentalStatus.RETURNED, "return")
        notifyVehicleReturned(updatedRental)
        
        if (hoursLate > GRACE_PERIOD_HOURS) {
            return RentalResult.LateReturn(updatedRental, hoursLate, priceBreakdown.lateFee)
        }
        
        if (damageAssessment?.hasDamage == true) {
            return RentalResult.DamageReported(updatedRental, damageAssessment)
        }
        
        return RentalResult.Success(updatedRental, priceBreakdown)
    }
    
    fun cancelReservation(rentalId: String): RentalResult {
        val rental = rentals[rentalId] ?: return RentalResult.NotFound
        
        val transitionResult = stateMachine.validateTransition(
            rental.status,
            RentalStatus.AVAILABLE
        )
        
        if (transitionResult is TransitionResult.Invalid) {
            return RentalResult.InvalidTransition(rental.status, RentalStatus.AVAILABLE)
        }
        
        val previousStatus = rental.status
        rental.vehicle.status = RentalStatus.AVAILABLE
        rentals.remove(rentalId)
        
        recordTransition(rentalId, previousStatus, RentalStatus.AVAILABLE, "cancel")
        
        return RentalResult.Success(
            rental.copy(status = RentalStatus.AVAILABLE),
            PriceBreakdown(0.0, 0.0)
        )
    }
    
    fun sendToMaintenance(vehicleId: String, reason: String = ""): RentalResult {
        val vehicle = vehicles[vehicleId] 
            ?: return RentalResult.VehicleUnavailable(vehicleId, "Vehicle not found")
        
        val transitionResult = stateMachine.validateTransition(
            vehicle.status,
            RentalStatus.UNDER_MAINTENANCE
        )
        
        if (transitionResult is TransitionResult.Invalid) {
            return RentalResult.InvalidTransition(vehicle.status, RentalStatus.UNDER_MAINTENANCE)
        }
        
        val previousStatus = vehicle.status
        vehicle.status = RentalStatus.UNDER_MAINTENANCE
        
        recordTransition(vehicleId, previousStatus, RentalStatus.UNDER_MAINTENANCE, "maintenance: $reason")
        notifyMaintenanceRequired(vehicle)
        
        return RentalResult.Success(
            Rental(
                id = "",
                vehicle = vehicle,
                customer = Customer("", "", "", ""),
                period = RentalPeriod(LocalDateTime.now(), LocalDateTime.now())
            ),
            PriceBreakdown(0.0, 0.0)
        )
    }
    
    fun completeMaintenanceAndMakeAvailable(vehicleId: String): RentalResult {
        val vehicle = vehicles[vehicleId] 
            ?: return RentalResult.VehicleUnavailable(vehicleId, "Vehicle not found")
        
        val transitionResult = stateMachine.validateTransition(
            vehicle.status,
            RentalStatus.AVAILABLE
        )
        
        if (transitionResult is TransitionResult.Invalid) {
            return RentalResult.InvalidTransition(vehicle.status, RentalStatus.AVAILABLE)
        }
        
        val previousStatus = vehicle.status
        vehicle.status = RentalStatus.AVAILABLE
        
        recordTransition(vehicleId, previousStatus, RentalStatus.AVAILABLE, "maintenance_complete")
        
        return RentalResult.Success(
            Rental(
                id = "",
                vehicle = vehicle,
                customer = Customer("", "", "", ""),
                period = RentalPeriod(LocalDateTime.now(), LocalDateTime.now())
            ),
            PriceBreakdown(0.0, 0.0)
        )
    }
    
    fun retireVehicle(vehicleId: String): RentalResult {
        val vehicle = vehicles[vehicleId] 
            ?: return RentalResult.VehicleUnavailable(vehicleId, "Vehicle not found")
        
        val transitionResult = stateMachine.validateTransition(
            vehicle.status,
            RentalStatus.RETIRED
        )
        
        if (transitionResult is TransitionResult.Invalid) {
            return RentalResult.InvalidTransition(vehicle.status, RentalStatus.RETIRED)
        }
        
        val previousStatus = vehicle.status
        vehicle.status = RentalStatus.RETIRED
        
        recordTransition(vehicleId, previousStatus, RentalStatus.RETIRED, "retire")
        
        return RentalResult.Success(
            Rental(
                id = "",
                vehicle = vehicle,
                customer = Customer("", "", "", ""),
                period = RentalPeriod(LocalDateTime.now(), LocalDateTime.now())
            ),
            PriceBreakdown(0.0, 0.0)
        )
    }
    
    fun getAvailableVehicles(): List<Vehicle> {
        return vehicles.values.filter { it.status == RentalStatus.AVAILABLE }
    }
    
    fun getAvailableVehiclesByType(type: VehicleType): List<Vehicle> {
        return vehicles.values.filter { 
            it.status == RentalStatus.AVAILABLE && it.type == type 
        }
    }
    
    fun getTransitionHistory(entityId: String? = null): List<TransitionRecord> {
        return if (entityId != null) {
            transitionHistory.filter { it.entityId == entityId }
        } else {
            transitionHistory.toList()
        }
    }
    
    private fun calculateLateHours(scheduledReturn: LocalDateTime, actualReturn: LocalDateTime): Long {
        if (actualReturn.isBefore(scheduledReturn) || actualReturn.isEqual(scheduledReturn)) {
            return 0
        }
        return Duration.between(scheduledReturn, actualReturn).toHours()
    }
    
    private fun recordTransition(
        entityId: String,
        from: RentalStatus,
        to: RentalStatus,
        action: String
    ) {
        transitionHistory.add(
            TransitionRecord(
                entityId = entityId,
                from = from,
                to = to,
                action = action,
                timestamp = LocalDateTime.now()
            )
        )
    }
    
    fun addObserver(observer: RentalObserver) {
        observers.add(observer)
    }
    
    fun removeObserver(observer: RentalObserver) {
        observers.remove(observer)
    }
    
    private fun notifyRentalCreated(rental: Rental) {
        observers.forEach { it.onRentalCreated(rental) }
    }
    
    private fun notifyVehiclePickedUp(rental: Rental) {
        observers.forEach { it.onVehiclePickedUp(rental) }
    }
    
    private fun notifyVehicleReturned(rental: Rental) {
        observers.forEach { it.onVehicleReturned(rental) }
    }
    
    private fun notifyLateReturn(rental: Rental, hoursLate: Long) {
        observers.forEach { it.onLateReturn(rental, hoursLate) }
    }
    
    private fun notifyDamageReported(rental: Rental, assessment: DamageAssessment) {
        observers.forEach { it.onDamageReported(rental, assessment) }
    }
    
    private fun notifyMaintenanceRequired(vehicle: Vehicle) {
        observers.forEach { it.onMaintenanceRequired(vehicle) }
    }
    
    companion object {
        private const val GRACE_PERIOD_HOURS = 1L
    }
}

/** Transition record for audit */
data class TransitionRecord(
    val entityId: String,
    val from: RentalStatus,
    val to: RentalStatus,
    val action: String,
    val timestamp: LocalDateTime
)

/** Simple pricing engine for state machine approach */
class SimplePricingEngine {
    
    fun calculatePrice(vehicle: Vehicle, period: RentalPeriod, addons: List<Addon>): PriceBreakdown {
        val days = period.getDays()
        val baseCost = vehicle.dailyRate * days
        val addonsCost = addons.sumOf { it.dailyPrice * days }
        val taxes = (baseCost + addonsCost) * TAX_RATE
        
        return PriceBreakdown(
            baseCost = baseCost,
            addonsCost = addonsCost,
            taxes = taxes
        )
    }
    
    fun calculateLateFee(vehicle: Vehicle, hoursLate: Long): Double {
        if (hoursLate <= GRACE_PERIOD_HOURS) return 0.0
        
        val chargeableHours = hoursLate - GRACE_PERIOD_HOURS
        val lateDays = (chargeableHours / 24.0).coerceAtLeast(0.5)
        
        return vehicle.dailyRate * lateDays * LATE_FEE_MULTIPLIER
    }
    
    companion object {
        private const val TAX_RATE = 0.08
        private const val GRACE_PERIOD_HOURS = 1L
        private const val LATE_FEE_MULTIPLIER = 1.5
    }
}
