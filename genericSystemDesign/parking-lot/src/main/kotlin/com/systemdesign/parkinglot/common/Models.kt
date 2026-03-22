package com.systemdesign.parkinglot.common

import java.time.LocalDateTime
import java.util.UUID

/**
 * Core domain models for Parking Lot System.
 * 
 * Extensibility Points:
 * - New vehicle types: Add to VehicleType enum
 * - New spot types: Add to SpotType enum
 * - New pricing strategies: Implement PricingStrategy interface
 * - New spot assignment strategies: Implement SpotAssignmentStrategy interface
 * 
 * Breaking Changes Required For:
 * - Changing ticket structure (requires migration)
 * - Adding multi-tenant support (requires gate/lot relationship changes)
 */

/** Types of vehicles */
enum class VehicleType {
    MOTORCYCLE,
    CAR,
    COMPACT_CAR,
    LARGE_CAR,
    ELECTRIC_CAR,
    HANDICAPPED_VEHICLE,
    TRUCK
}

/** Types of parking spots */
enum class SpotType {
    MOTORCYCLE,
    COMPACT,
    REGULAR,
    LARGE,
    ELECTRIC,
    HANDICAPPED
}

/** Which spot types can accommodate which vehicle types */
val VEHICLE_SPOT_COMPATIBILITY = mapOf(
    VehicleType.MOTORCYCLE to listOf(SpotType.MOTORCYCLE, SpotType.COMPACT, SpotType.REGULAR, SpotType.LARGE),
    VehicleType.COMPACT_CAR to listOf(SpotType.COMPACT, SpotType.REGULAR, SpotType.LARGE),
    VehicleType.CAR to listOf(SpotType.REGULAR, SpotType.LARGE),
    VehicleType.LARGE_CAR to listOf(SpotType.LARGE),
    VehicleType.ELECTRIC_CAR to listOf(SpotType.ELECTRIC, SpotType.REGULAR, SpotType.LARGE),
    VehicleType.HANDICAPPED_VEHICLE to listOf(SpotType.HANDICAPPED, SpotType.REGULAR, SpotType.LARGE),
    VehicleType.TRUCK to listOf(SpotType.LARGE)
)

/** Represents a vehicle */
data class Vehicle(
    val licensePlate: String,
    val type: VehicleType
)

/** Represents a parking spot */
data class ParkingSpot(
    val id: String,
    val type: SpotType,
    val floor: Int,
    val row: Int,
    val number: Int,
    var isOccupied: Boolean = false,
    var parkedVehicle: Vehicle? = null
) {
    fun canFit(vehicle: Vehicle): Boolean {
        if (isOccupied) return false
        return VEHICLE_SPOT_COMPATIBILITY[vehicle.type]?.contains(type) == true
    }
    
    fun park(vehicle: Vehicle): Boolean {
        if (!canFit(vehicle)) return false
        isOccupied = true
        parkedVehicle = vehicle
        return true
    }
    
    fun unpark(): Vehicle? {
        val vehicle = parkedVehicle
        isOccupied = false
        parkedVehicle = null
        return vehicle
    }
}

/** Represents a parking floor */
data class ParkingFloor(
    val floorNumber: Int,
    val spots: MutableList<ParkingSpot> = mutableListOf()
) {
    fun getAvailableSpots(vehicleType: VehicleType): List<ParkingSpot> {
        val compatibleTypes = VEHICLE_SPOT_COMPATIBILITY[vehicleType] ?: emptyList()
        return spots.filter { spot ->
            !spot.isOccupied && compatibleTypes.contains(spot.type)
        }
    }
    
    fun getAvailableCount(): Int = spots.count { !it.isOccupied }
    
    fun getTotalCount(): Int = spots.size
}

/** Parking ticket issued at entry */
data class ParkingTicket(
    val ticketId: String = UUID.randomUUID().toString(),
    val vehicle: Vehicle,
    val spot: ParkingSpot,
    val entryTime: LocalDateTime = LocalDateTime.now(),
    var exitTime: LocalDateTime? = null,
    var fee: Double = 0.0,
    var isPaid: Boolean = false,
    var isLost: Boolean = false
)

/** Entry/Exit gate types */
enum class GateType {
    ENTRY,
    EXIT
}

/** Gate status */
enum class GateStatus {
    OPEN,
    CLOSED,
    BLOCKED
}

/** Represents a gate */
data class Gate(
    val id: String,
    val type: GateType,
    var status: GateStatus = GateStatus.CLOSED
)

/** Pricing strategy interface */
interface PricingStrategy {
    fun calculateFee(ticket: ParkingTicket): Double
}

/** Spot assignment strategy interface */
interface SpotAssignmentStrategy {
    fun findSpot(vehicle: Vehicle, floors: List<ParkingFloor>): ParkingSpot?
}

/** Observer for parking lot events */
interface ParkingLotObserver {
    fun onVehicleEntered(ticket: ParkingTicket)
    fun onVehicleExited(ticket: ParkingTicket)
    fun onLotFull()
    fun onLotAvailable()
}

/** Parking lot statistics */
data class ParkingLotStats(
    val totalSpots: Int,
    val occupiedSpots: Int,
    val availableSpots: Int,
    val spotsPerType: Map<SpotType, Int>,
    val availablePerType: Map<SpotType, Int>
)
