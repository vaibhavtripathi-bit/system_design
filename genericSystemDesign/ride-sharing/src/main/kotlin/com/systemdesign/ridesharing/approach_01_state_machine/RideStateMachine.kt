package com.systemdesign.ridesharing.approach_01_state_machine

import com.systemdesign.ridesharing.common.*
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Approach 1: State Machine Pattern for Ride Lifecycle
 * 
 * The ride is modeled as an explicit finite state machine.
 * Each state has specific valid operations and transitions.
 * 
 * Pattern: State Machine
 * 
 * Trade-offs:
 * + Clear state transitions prevent invalid operations
 * + Easy to reason about ride lifecycle
 * + State-specific validation and timeout handling
 * + Audit trail of all state transitions
 * - More boilerplate for state management
 * - State explosion if too many orthogonal concerns
 * 
 * When to use:
 * - When the entity has clear discrete states (rides, orders, etc.)
 * - When operations are only valid in certain states
 * - When timeout handling differs per state
 * 
 * Extensibility:
 * - New state: Add to RideState enum and update transition table
 * - New transition guard: Add condition in canTransition()
 * - New timeout: Add to stateTimeouts map
 * - New cancellation policy: Implement CancellationPolicy interface
 */

/** State transition definition */
data class StateTransition(
    val from: RideState,
    val to: RideState,
    val guard: (Ride) -> Boolean = { true },
    val action: ((Ride) -> Ride)? = null
)

/** Timeout configuration per state */
data class StateTimeout(
    val state: RideState,
    val duration: Duration,
    val onTimeout: (Ride) -> RideState
)

/**
 * State machine for managing ride lifecycle
 */
class RideStateMachine(
    private val matchingStrategy: DriverMatchingStrategy,
    private val pricingStrategy: PricingStrategy,
    private val cancellationPolicy: CancellationPolicy = DefaultCancellationPolicy()
) {
    private val rides = ConcurrentHashMap<String, Ride>()
    private val observers = CopyOnWriteArrayList<RideObserver>()
    private val rideTimestamps = ConcurrentHashMap<String, LocalDateTime>()
    
    private val validTransitions = mapOf(
        RideState.REQUESTED to setOf(
            RideState.MATCHING,
            RideState.CANCELLED
        ),
        RideState.MATCHING to setOf(
            RideState.DRIVER_ASSIGNED,
            RideState.CANCELLED
        ),
        RideState.DRIVER_ASSIGNED to setOf(
            RideState.ARRIVED,
            RideState.CANCELLED
        ),
        RideState.ARRIVED to setOf(
            RideState.IN_PROGRESS,
            RideState.CANCELLED
        ),
        RideState.IN_PROGRESS to setOf(
            RideState.COMPLETED,
            RideState.CANCELLED
        ),
        RideState.COMPLETED to emptySet(),
        RideState.CANCELLED to emptySet()
    )
    
    private val stateTimeouts = mapOf(
        RideState.MATCHING to StateTimeout(
            state = RideState.MATCHING,
            duration = Duration.ofMinutes(5),
            onTimeout = { RideState.CANCELLED }
        ),
        RideState.DRIVER_ASSIGNED to StateTimeout(
            state = RideState.DRIVER_ASSIGNED,
            duration = Duration.ofMinutes(15),
            onTimeout = { RideState.CANCELLED }
        ),
        RideState.ARRIVED to StateTimeout(
            state = RideState.ARRIVED,
            duration = Duration.ofMinutes(5),
            onTimeout = { RideState.CANCELLED }
        )
    )
    
    fun requestRide(request: RideRequest): Ride {
        val ride = Ride(request = request, state = RideState.REQUESTED)
        rides[ride.id] = ride
        rideTimestamps[ride.id] = LocalDateTime.now()
        
        notifyObservers(RideEvent.RideRequested(ride.id, request))
        return ride
    }
    
    fun startMatching(rideId: String, availableDrivers: List<Driver>): Ride? {
        val ride = rides[rideId] ?: return null
        
        if (!canTransition(ride, RideState.MATCHING)) {
            return null
        }
        
        val updatedRide = ride.copy(
            state = RideState.MATCHING,
            matchAttempts = ride.matchAttempts + 1
        )
        rides[rideId] = updatedRide
        rideTimestamps[rideId] = LocalDateTime.now()
        
        notifyObservers(RideEvent.MatchingStarted(rideId, searchRadius = 5.0))
        
        val matchResult = matchingStrategy.findBestMatch(
            ride.request,
            availableDrivers
        )
        
        return if (matchResult != null) {
            assignDriver(rideId, matchResult)
        } else {
            if (updatedRide.matchAttempts >= 3) {
                cancelRide(rideId, CancellationReason.NO_DRIVERS_AVAILABLE, "system")
            }
            updatedRide
        }
    }
    
    fun assignDriver(rideId: String, matchResult: MatchResult): Ride? {
        val ride = rides[rideId] ?: return null
        
        if (!canTransition(ride, RideState.DRIVER_ASSIGNED)) {
            return null
        }
        
        val route = Route.direct(ride.request.pickup, ride.request.dropoff)
        val updatedRide = ride.copy(
            state = RideState.DRIVER_ASSIGNED,
            driver = matchResult.driver,
            route = route
        )
        rides[rideId] = updatedRide
        rideTimestamps[rideId] = LocalDateTime.now()
        
        notifyObservers(RideEvent.DriverAssigned(
            rideId, 
            matchResult.driver, 
            matchResult.etaMinutes
        ))
        
        return updatedRide
    }
    
    fun driverArrived(rideId: String): Ride? {
        val ride = rides[rideId] ?: return null
        
        if (!canTransition(ride, RideState.ARRIVED)) {
            return null
        }
        
        val driver = ride.driver ?: return null
        
        val updatedRide = ride.copy(state = RideState.ARRIVED)
        rides[rideId] = updatedRide
        rideTimestamps[rideId] = LocalDateTime.now()
        
        notifyObservers(RideEvent.DriverArrived(rideId, driver.id))
        
        return updatedRide
    }
    
    fun startRide(rideId: String): Ride? {
        val ride = rides[rideId] ?: return null
        
        if (!canTransition(ride, RideState.IN_PROGRESS)) {
            return null
        }
        
        val updatedRide = ride.copy(
            state = RideState.IN_PROGRESS,
            startTime = LocalDateTime.now()
        )
        rides[rideId] = updatedRide
        rideTimestamps[rideId] = LocalDateTime.now()
        
        notifyObservers(RideEvent.RideStarted(rideId, ride.request.pickup))
        
        return updatedRide
    }
    
    fun completeRide(rideId: String, actualRoute: Route? = null): Ride? {
        val ride = rides[rideId] ?: return null
        
        if (!canTransition(ride, RideState.COMPLETED)) {
            return null
        }
        
        val finalRoute = actualRoute ?: ride.route ?: Route.direct(
            ride.request.pickup, 
            ride.request.dropoff
        )
        
        val rideWithRoute = ride.copy(
            route = finalRoute,
            endTime = LocalDateTime.now()
        )
        
        val price = pricingStrategy.calculatePrice(rideWithRoute)
        
        val updatedRide = rideWithRoute.copy(
            state = RideState.COMPLETED,
            price = price
        )
        rides[rideId] = updatedRide
        
        notifyObservers(RideEvent.RideCompleted(rideId, price, finalRoute))
        
        return updatedRide
    }
    
    fun cancelRide(
        rideId: String, 
        reason: CancellationReason, 
        cancelledBy: String
    ): Ride? {
        val ride = rides[rideId] ?: return null
        
        if (!canTransition(ride, RideState.CANCELLED)) {
            return null
        }
        
        if (!cancellationPolicy.canCancel(ride, cancelledBy)) {
            return null
        }
        
        val cancellationFee = cancellationPolicy.getCancellationFee(ride, cancelledBy)
        
        val cancellation = Cancellation(
            reason = reason,
            cancelledBy = cancelledBy,
            cancellationFee = cancellationFee
        )
        
        val updatedRide = ride.copy(
            state = RideState.CANCELLED,
            cancellation = cancellation,
            endTime = LocalDateTime.now()
        )
        rides[rideId] = updatedRide
        
        notifyObservers(RideEvent.RideCancelled(
            rideId, 
            reason, 
            cancelledBy, 
            cancellationFee
        ))
        
        return updatedRide
    }
    
    fun updateDriverLocation(rideId: String, newLocation: Location): Ride? {
        val ride = rides[rideId] ?: return null
        val driver = ride.driver ?: return null
        
        if (ride.state !in listOf(RideState.DRIVER_ASSIGNED, RideState.ARRIVED, RideState.IN_PROGRESS)) {
            return null
        }
        
        val updatedDriver = driver.withLocation(newLocation)
        val updatedRide = ride.copy(driver = updatedDriver)
        rides[rideId] = updatedRide
        
        val eta = calculateEta(newLocation, ride.request.pickup)
        notifyObservers(RideEvent.DriverLocationUpdated(rideId, driver.id, newLocation, eta))
        
        return updatedRide
    }
    
    fun checkTimeouts(): List<Ride> {
        val now = LocalDateTime.now()
        val timedOutRides = mutableListOf<Ride>()
        
        for ((rideId, ride) in rides) {
            if (ride.state.isTerminal()) continue
            
            val timeout = stateTimeouts[ride.state] ?: continue
            val stateEnteredAt = rideTimestamps[rideId] ?: continue
            
            if (Duration.between(stateEnteredAt, now) > timeout.duration) {
                val reason = when (ride.state) {
                    RideState.MATCHING -> CancellationReason.NO_DRIVERS_AVAILABLE
                    RideState.DRIVER_ASSIGNED -> CancellationReason.DRIVER_NO_SHOW
                    RideState.ARRIVED -> CancellationReason.RIDER_NO_SHOW
                    else -> CancellationReason.TIMEOUT
                }
                
                notifyObservers(RideEvent.MatchingTimeout(rideId, ride.matchAttempts))
                
                cancelRide(rideId, reason, "system")?.let {
                    timedOutRides.add(it)
                }
            }
        }
        
        return timedOutRides
    }
    
    fun getRide(rideId: String): Ride? = rides[rideId]
    
    fun getRideState(rideId: String): RideState? = rides[rideId]?.state
    
    fun getActiveRides(): List<Ride> = rides.values.filter { it.state.isActive() }
    
    fun getRidesByState(state: RideState): List<Ride> = rides.values.filter { it.state == state }
    
    private fun canTransition(ride: Ride, to: RideState): Boolean {
        val allowedTargets = validTransitions[ride.state] ?: return false
        if (to !in allowedTargets) return false
        
        return when (to) {
            RideState.DRIVER_ASSIGNED -> ride.state == RideState.MATCHING
            RideState.IN_PROGRESS -> ride.driver != null && ride.state == RideState.ARRIVED
            RideState.COMPLETED -> ride.startTime != null && ride.state == RideState.IN_PROGRESS
            else -> true
        }
    }
    
    private fun calculateEta(from: Location, to: Location): Int {
        val distance = from.distanceTo(to)
        return (distance / 30 * 60).toInt() // 30 km/h average city speed
    }
    
    fun addObserver(observer: RideObserver) {
        observers.add(observer)
    }
    
    fun removeObserver(observer: RideObserver) {
        observers.remove(observer)
    }
    
    private fun notifyObservers(event: RideEvent) {
        observers.forEach { it.onRideEvent(event) }
    }
}

/**
 * Default cancellation policy with time-based fees
 */
class DefaultCancellationPolicy : CancellationPolicy {
    
    private val freeCancellationStates = setOf(
        RideState.REQUESTED,
        RideState.MATCHING
    )
    
    private val riderCancellationFees = mapOf(
        RideState.DRIVER_ASSIGNED to 5.0,
        RideState.ARRIVED to 10.0,
        RideState.IN_PROGRESS to 15.0
    )
    
    private val driverCancellationFees = mapOf(
        RideState.DRIVER_ASSIGNED to 0.0,
        RideState.ARRIVED to 0.0,
        RideState.IN_PROGRESS to 20.0
    )
    
    override fun canCancel(ride: Ride, cancelledBy: String): Boolean {
        if (ride.state.isTerminal()) return false
        
        // System can always cancel
        if (cancelledBy == "system") return true
        
        // Riders can cancel at any non-terminal state
        if (cancelledBy == ride.request.rider.id) return true
        
        // Drivers can cancel only if assigned to the ride
        if (ride.driver != null && cancelledBy == ride.driver.id) {
            return ride.state != RideState.IN_PROGRESS || 
                   ride.startTime == null
        }
        
        return false
    }
    
    override fun getCancellationFee(ride: Ride, cancelledBy: String): Double {
        if (ride.state in freeCancellationStates) return 0.0
        
        return when {
            cancelledBy == "system" -> 0.0
            cancelledBy == ride.request.rider.id -> riderCancellationFees[ride.state] ?: 0.0
            ride.driver != null && cancelledBy == ride.driver.id -> driverCancellationFees[ride.state] ?: 0.0
            else -> 0.0
        }
    }
}

/**
 * Premium cancellation policy with stricter fees
 */
class PremiumCancellationPolicy : CancellationPolicy {
    
    override fun canCancel(ride: Ride, cancelledBy: String): Boolean {
        if (ride.state.isTerminal()) return false
        
        // Premium rides cannot be cancelled after driver starts moving
        if (ride.request.rideType == RideType.PREMIUM && 
            ride.state in listOf(RideState.ARRIVED, RideState.IN_PROGRESS)) {
            return cancelledBy == "system"
        }
        
        return true
    }
    
    override fun getCancellationFee(ride: Ride, cancelledBy: String): Double {
        val baseFee = when (ride.state) {
            RideState.REQUESTED, RideState.MATCHING -> 0.0
            RideState.DRIVER_ASSIGNED -> 7.5
            RideState.ARRIVED -> 15.0
            RideState.IN_PROGRESS -> 25.0
            else -> 0.0
        }
        
        // Premium rides have higher cancellation fees
        return if (ride.request.rideType == RideType.PREMIUM) {
            baseFee * 1.5
        } else {
            baseFee
        }
    }
}

/**
 * Builder for creating ride state machines with custom configuration
 */
class RideStateMachineBuilder {
    private var matchingStrategy: DriverMatchingStrategy = NearestDriverMatcher()
    private var pricingStrategy: PricingStrategy = DefaultPricingStrategy()
    private var cancellationPolicy: CancellationPolicy = DefaultCancellationPolicy()
    
    fun withMatchingStrategy(strategy: DriverMatchingStrategy): RideStateMachineBuilder {
        matchingStrategy = strategy
        return this
    }
    
    fun withPricingStrategy(strategy: PricingStrategy): RideStateMachineBuilder {
        pricingStrategy = strategy
        return this
    }
    
    fun withCancellationPolicy(policy: CancellationPolicy): RideStateMachineBuilder {
        cancellationPolicy = policy
        return this
    }
    
    fun build(): RideStateMachine {
        return RideStateMachine(matchingStrategy, pricingStrategy, cancellationPolicy)
    }
}

/** Simple nearest driver matcher for default use */
private class NearestDriverMatcher : DriverMatchingStrategy {
    override fun findBestMatch(
        request: RideRequest,
        availableDrivers: List<Driver>,
        surgeZones: List<SurgeZone>
    ): MatchResult? {
        return availableDrivers
            .filter { it.canAccept(request.rideType) }
            .map { MatchResult.fromDriverAndPickup(it, request.pickup) }
            .minByOrNull { it.distance }
    }
}

/** Simple pricing strategy for default use */
private class DefaultPricingStrategy : PricingStrategy {
    override fun calculatePrice(ride: Ride): Price {
        val distance = ride.route?.distanceKm ?: ride.request.estimatedDistance()
        val duration = ride.route?.estimatedDurationMinutes ?: 15
        
        return Price(
            baseFare = 3.0 * ride.request.rideType.baseMultiplier,
            distanceCharge = distance * 1.5,
            timeCharge = duration * 0.2
        )
    }
}
