package com.systemdesign.elevator.common

/**
 * Core domain models for the Elevator System.
 * 
 * Extensibility Points:
 * - New elevator types: Implement Elevator interface
 * - New dispatch algorithms: Implement DispatchStrategy interface
 * - New floor types (VIP, restricted): Add to FloorType enum or create FloorAccessPolicy
 * 
 * Breaking Changes Required For:
 * - Changing the state machine transitions (requires state machine refactor)
 * - Adding bi-directional communication (current model is command-based)
 */

/** Represents the direction an elevator is moving */
enum class Direction {
    UP, DOWN, IDLE
}

/** State of an elevator in the state machine */
enum class ElevatorState {
    IDLE,
    MOVING_UP,
    MOVING_DOWN,
    STOPPED,
    DOORS_OPENING,
    DOORS_OPEN,
    DOORS_CLOSING,
    EMERGENCY_STOP,
    MAINTENANCE
}

/** Types of floors for access control extensibility */
enum class FloorType {
    NORMAL,
    VIP,
    RESTRICTED,
    BASEMENT,
    ROOF
}

/** Request from inside the elevator cabin */
data class CabinRequest(
    val targetFloor: Int,
    val timestamp: Long = System.currentTimeMillis()
)

/** Request from outside (floor button press) */
data class FloorRequest(
    val floor: Int,
    val direction: Direction,
    val timestamp: Long = System.currentTimeMillis()
)

/** Unified request type */
sealed class ElevatorRequest {
    abstract val floor: Int
    abstract val timestamp: Long
    
    data class Internal(
        override val floor: Int,
        override val timestamp: Long = System.currentTimeMillis()
    ) : ElevatorRequest()
    
    data class External(
        override val floor: Int,
        val direction: Direction,
        override val timestamp: Long = System.currentTimeMillis()
    ) : ElevatorRequest()
}

/** Elevator status for monitoring */
data class ElevatorStatus(
    val id: String,
    val currentFloor: Int,
    val state: ElevatorState,
    val direction: Direction,
    val pendingRequests: List<ElevatorRequest>,
    val currentLoad: Int,
    val maxCapacity: Int
)

/** Events that can occur in the elevator system */
sealed class ElevatorEvent {
    data class FloorReached(val elevatorId: String, val floor: Int) : ElevatorEvent()
    data class DoorsOpened(val elevatorId: String) : ElevatorEvent()
    data class DoorsClosed(val elevatorId: String) : ElevatorEvent()
    data class DoorBlocked(val elevatorId: String) : ElevatorEvent()
    data class OverweightDetected(val elevatorId: String, val currentLoad: Int) : ElevatorEvent()
    data class EmergencyStop(val elevatorId: String, val reason: String) : ElevatorEvent()
    data class MaintenanceMode(val elevatorId: String, val enabled: Boolean) : ElevatorEvent()
}

/** Observer interface for elevator events */
interface ElevatorObserver {
    fun onEvent(event: ElevatorEvent)
}

/** Sensor interface for door and weight detection */
interface ElevatorSensor {
    fun isDoorBlocked(): Boolean
    fun getCurrentWeight(): Int
}

/** Default sensor implementation */
class DefaultElevatorSensor : ElevatorSensor {
    private var doorBlocked = false
    private var currentWeight = 0
    
    fun setDoorBlocked(blocked: Boolean) { doorBlocked = blocked }
    fun setCurrentWeight(weight: Int) { currentWeight = weight }
    
    override fun isDoorBlocked(): Boolean = doorBlocked
    override fun getCurrentWeight(): Int = currentWeight
}

/** Elevator interface - core abstraction */
interface Elevator {
    val id: String
    val minFloor: Int
    val maxFloor: Int
    val maxCapacity: Int
    
    fun getStatus(): ElevatorStatus
    fun addRequest(request: ElevatorRequest)
    fun emergencyStop(reason: String)
    fun setMaintenanceMode(enabled: Boolean)
    fun step(): ElevatorEvent?
    fun addObserver(observer: ElevatorObserver)
    fun removeObserver(observer: ElevatorObserver)
}

/** Dispatch strategy interface - Strategy Pattern */
interface DispatchStrategy {
    fun selectElevator(
        request: ElevatorRequest.External,
        elevators: List<Elevator>
    ): Elevator?
}

/** Building controller interface */
interface ElevatorController {
    fun requestElevator(floor: Int, direction: Direction)
    fun requestFloor(elevatorId: String, floor: Int)
    fun getElevatorStatus(elevatorId: String): ElevatorStatus?
    fun getAllStatus(): List<ElevatorStatus>
    fun emergencyStopAll(reason: String)
}
