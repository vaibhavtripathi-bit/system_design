package com.systemdesign.parkinglot.approach_01_strategy_assignment

import com.systemdesign.parkinglot.common.*
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Approach 1: Strategy Pattern for Spot Assignment
 * 
 * Different spot assignment algorithms can be swapped at runtime.
 * Examples: nearest to entrance, by floor preference, by spot type preference.
 * 
 * Pattern: Strategy Pattern
 * 
 * Trade-offs:
 * + Different assignment policies for different times (peak vs off-peak)
 * + Easy to add new assignment algorithms
 * + Algorithms testable in isolation
 * - Assignment strategy needs full lot visibility
 * 
 * When to use:
 * - When assignment policy varies by time/customer type
 * - When A/B testing different assignment strategies
 * - When optimizing for different metrics (utilization, walking distance)
 * 
 * Extensibility:
 * - New assignment strategy: Implement SpotAssignmentStrategy
 * - VIP parking: Create VIPAssignmentStrategy that prioritizes premium spots
 */

/** Assigns nearest spot to the entrance (lowest floor, lowest number) */
class NearestSpotStrategy : SpotAssignmentStrategy {
    override fun findSpot(vehicle: Vehicle, floors: List<ParkingFloor>): ParkingSpot? {
        return floors
            .sortedBy { it.floorNumber }
            .flatMap { it.getAvailableSpots(vehicle.type) }
            .minByOrNull { it.row * 100 + it.number }
    }
}

/** Assigns spot by type preference (exact match first, then fallback) */
class TypePreferenceStrategy : SpotAssignmentStrategy {
    override fun findSpot(vehicle: Vehicle, floors: List<ParkingFloor>): ParkingSpot? {
        val preferredType = when (vehicle.type) {
            VehicleType.MOTORCYCLE -> SpotType.MOTORCYCLE
            VehicleType.COMPACT_CAR -> SpotType.COMPACT
            VehicleType.ELECTRIC_CAR -> SpotType.ELECTRIC
            VehicleType.HANDICAPPED_VEHICLE -> SpotType.HANDICAPPED
            else -> SpotType.REGULAR
        }
        
        // First try exact match
        val exactMatch = floors
            .flatMap { it.spots }
            .filter { !it.isOccupied && it.type == preferredType }
            .firstOrNull()
        
        if (exactMatch != null) return exactMatch
        
        // Fall back to any compatible spot
        return floors
            .flatMap { it.getAvailableSpots(vehicle.type) }
            .firstOrNull()
    }
}

/** Assigns spots by floor preference (distributes load across floors) */
class FloorDistributionStrategy : SpotAssignmentStrategy {
    override fun findSpot(vehicle: Vehicle, floors: List<ParkingFloor>): ParkingSpot? {
        // Prefer floor with most availability to spread the load
        return floors
            .sortedByDescending { it.getAvailableSpots(vehicle.type).size }
            .firstNotNullOfOrNull { floor ->
                floor.getAvailableSpots(vehicle.type).firstOrNull()
            }
    }
}

/** Assigns electric vehicles to EV spots, falls back to regular */
class EVPriorityStrategy : SpotAssignmentStrategy {
    private val fallback = NearestSpotStrategy()
    
    override fun findSpot(vehicle: Vehicle, floors: List<ParkingFloor>): ParkingSpot? {
        if (vehicle.type == VehicleType.ELECTRIC_CAR) {
            // Try EV spots first
            val evSpot = floors
                .flatMap { it.spots }
                .filter { !it.isOccupied && it.type == SpotType.ELECTRIC }
                .firstOrNull()
            
            if (evSpot != null) return evSpot
        }
        
        return fallback.findSpot(vehicle, floors)
    }
}

/**
 * Parking lot implementation using strategy pattern for spot assignment
 */
class StrategyParkingLot(
    val lotId: String,
    val floors: List<ParkingFloor>,
    private val entryGates: List<Gate>,
    private val exitGates: List<Gate>,
    private var assignmentStrategy: SpotAssignmentStrategy = NearestSpotStrategy(),
    private val pricingStrategy: PricingStrategy = HourlyPricingStrategy()
) {
    private val activeTickets = ConcurrentHashMap<String, ParkingTicket>()
    private val vehicleTicketMap = ConcurrentHashMap<String, String>()
    private val observers = CopyOnWriteArrayList<ParkingLotObserver>()
    private val lock = ReentrantReadWriteLock()
    
    fun setAssignmentStrategy(strategy: SpotAssignmentStrategy) {
        assignmentStrategy = strategy
    }
    
    fun enter(vehicle: Vehicle, gateId: String): ParkingTicket? {
        val gate = entryGates.find { it.id == gateId } ?: return null
        
        return lock.write {
            // Check if vehicle already parked
            if (vehicleTicketMap.containsKey(vehicle.licensePlate)) {
                return@write null
            }
            
            val spot = assignmentStrategy.findSpot(vehicle, floors)
            if (spot == null) {
                notifyLotFull()
                return@write null
            }
            
            if (!spot.park(vehicle)) {
                return@write null
            }
            
            val ticket = ParkingTicket(
                vehicle = vehicle,
                spot = spot
            )
            
            activeTickets[ticket.ticketId] = ticket
            vehicleTicketMap[vehicle.licensePlate] = ticket.ticketId
            
            notifyVehicleEntered(ticket)
            
            if (isFull()) {
                notifyLotFull()
            }
            
            ticket
        }
    }
    
    fun exit(ticketId: String, gateId: String): ParkingTicket? {
        val gate = exitGates.find { it.id == gateId } ?: return null
        
        return lock.write {
            val ticket = activeTickets[ticketId] ?: return@write null
            
            ticket.exitTime = LocalDateTime.now()
            ticket.fee = pricingStrategy.calculateFee(ticket)
            
            // Unpark the vehicle
            ticket.spot.unpark()
            
            activeTickets.remove(ticketId)
            vehicleTicketMap.remove(ticket.vehicle.licensePlate)
            
            notifyVehicleExited(ticket)
            
            val wasFullBefore = getStats().availableSpots == 1
            if (wasFullBefore) {
                notifyLotAvailable()
            }
            
            ticket
        }
    }
    
    fun exitWithLostTicket(licensePlate: String, gateId: String): ParkingTicket? {
        val ticketId = vehicleTicketMap[licensePlate] ?: return null
        
        return lock.write {
            val ticket = activeTickets[ticketId] ?: return@write null
            ticket.isLost = true
            exit(ticketId, gateId)
        }
    }
    
    fun getStats(): ParkingLotStats {
        return lock.read {
            val allSpots = floors.flatMap { it.spots }
            val total = allSpots.size
            val occupied = allSpots.count { it.isOccupied }
            
            val spotsPerType = allSpots.groupBy { it.type }
                .mapValues { it.value.size }
            
            val availablePerType = allSpots.filter { !it.isOccupied }
                .groupBy { it.type }
                .mapValues { it.value.size }
            
            ParkingLotStats(
                totalSpots = total,
                occupiedSpots = occupied,
                availableSpots = total - occupied,
                spotsPerType = spotsPerType,
                availablePerType = availablePerType
            )
        }
    }
    
    fun isFull(): Boolean {
        return lock.read {
            floors.flatMap { it.spots }.all { it.isOccupied }
        }
    }
    
    fun getAvailableSpots(vehicleType: VehicleType): Int {
        return lock.read {
            floors.sumOf { it.getAvailableSpots(vehicleType).size }
        }
    }
    
    fun addObserver(observer: ParkingLotObserver) {
        observers.add(observer)
    }
    
    fun removeObserver(observer: ParkingLotObserver) {
        observers.remove(observer)
    }
    
    private fun notifyVehicleEntered(ticket: ParkingTicket) {
        observers.forEach { it.onVehicleEntered(ticket) }
    }
    
    private fun notifyVehicleExited(ticket: ParkingTicket) {
        observers.forEach { it.onVehicleExited(ticket) }
    }
    
    private fun notifyLotFull() {
        observers.forEach { it.onLotFull() }
    }
    
    private fun notifyLotAvailable() {
        observers.forEach { it.onLotAvailable() }
    }
}

/** Hourly pricing strategy */
class HourlyPricingStrategy(
    private val baseRate: Double = 2.0,
    private val lostTicketPenalty: Double = 50.0
) : PricingStrategy {
    override fun calculateFee(ticket: ParkingTicket): Double {
        if (ticket.isLost) {
            return lostTicketPenalty
        }
        
        val exitTime = ticket.exitTime ?: LocalDateTime.now()
        val hours = ChronoUnit.HOURS.between(ticket.entryTime, exitTime).toInt() + 1
        
        return hours * baseRate
    }
}
