package com.systemdesign.elevator.approach_01_state_machine

import com.systemdesign.elevator.common.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.PriorityBlockingQueue

/**
 * Approach 1: State Machine Pattern
 * 
 * The elevator is modeled as an explicit finite state machine with well-defined
 * transitions. Each state has specific entry/exit behaviors and valid transitions.
 * 
 * Pattern: State Machine (explicit states with transition guards)
 * 
 * Trade-offs:
 * + Explicit states make behavior predictable and testable
 * + Invalid transitions are guarded at compile/runtime
 * + Easy to add new states (e.g., FIRE_ALARM_MODE)
 * - More verbose than implicit state handling
 * - State explosion if too many orthogonal concerns
 * 
 * When to use:
 * - When system behavior depends heavily on current state
 * - When invalid state transitions must be prevented
 * - When state history/audit is important
 * 
 * Extensibility:
 * - Add new state: Add to ElevatorState enum, update transition table
 * - Add VIP floor: Add FloorAccessPolicy check before processing request
 */
class StateMachineElevator(
    override val id: String,
    override val minFloor: Int = 0,
    override val maxFloor: Int = 10,
    override val maxCapacity: Int = 1000,
    private val sensor: ElevatorSensor = DefaultElevatorSensor(),
    private val doorOpenDuration: Int = 3
) : Elevator {
    
    private var currentFloor: Int = 0
    private var state: ElevatorState = ElevatorState.IDLE
    private var direction: Direction = Direction.IDLE
    private var doorTimer: Int = 0
    private var currentLoad: Int = 0
    private var maintenanceMode: Boolean = false
    
    private val upRequests = sortedSetOf<Int>()
    private val downRequests = sortedSetOf<Int>(Comparator.reverseOrder())
    private val observers = CopyOnWriteArrayList<ElevatorObserver>()
    
    // Valid state transitions
    private val validTransitions = mapOf(
        ElevatorState.IDLE to setOf(
            ElevatorState.MOVING_UP,
            ElevatorState.MOVING_DOWN,
            ElevatorState.DOORS_OPENING,
            ElevatorState.EMERGENCY_STOP,
            ElevatorState.MAINTENANCE
        ),
        ElevatorState.MOVING_UP to setOf(
            ElevatorState.STOPPED,
            ElevatorState.EMERGENCY_STOP
        ),
        ElevatorState.MOVING_DOWN to setOf(
            ElevatorState.STOPPED,
            ElevatorState.EMERGENCY_STOP
        ),
        ElevatorState.STOPPED to setOf(
            ElevatorState.DOORS_OPENING,
            ElevatorState.MOVING_UP,
            ElevatorState.MOVING_DOWN,
            ElevatorState.IDLE,
            ElevatorState.EMERGENCY_STOP
        ),
        ElevatorState.DOORS_OPENING to setOf(
            ElevatorState.DOORS_OPEN,
            ElevatorState.EMERGENCY_STOP
        ),
        ElevatorState.DOORS_OPEN to setOf(
            ElevatorState.DOORS_CLOSING,
            ElevatorState.EMERGENCY_STOP
        ),
        ElevatorState.DOORS_CLOSING to setOf(
            ElevatorState.DOORS_OPENING, // Door blocked
            ElevatorState.IDLE,
            ElevatorState.MOVING_UP,
            ElevatorState.MOVING_DOWN,
            ElevatorState.EMERGENCY_STOP
        ),
        ElevatorState.EMERGENCY_STOP to setOf(
            ElevatorState.IDLE,
            ElevatorState.MAINTENANCE
        ),
        ElevatorState.MAINTENANCE to setOf(
            ElevatorState.IDLE
        )
    )
    
    private fun canTransition(to: ElevatorState): Boolean {
        return validTransitions[state]?.contains(to) == true
    }
    
    private fun transition(to: ElevatorState): Boolean {
        if (!canTransition(to)) {
            return false
        }
        state = to
        return true
    }
    
    override fun getStatus(): ElevatorStatus {
        val allRequests = mutableListOf<ElevatorRequest>()
        upRequests.forEach { allRequests.add(ElevatorRequest.Internal(it)) }
        downRequests.forEach { allRequests.add(ElevatorRequest.Internal(it)) }
        
        return ElevatorStatus(
            id = id,
            currentFloor = currentFloor,
            state = state,
            direction = direction,
            pendingRequests = allRequests,
            currentLoad = currentLoad,
            maxCapacity = maxCapacity
        )
    }
    
    override fun addRequest(request: ElevatorRequest) {
        if (state == ElevatorState.MAINTENANCE || state == ElevatorState.EMERGENCY_STOP) {
            return
        }
        
        val floor = request.floor
        if (floor < minFloor || floor > maxFloor) return
        
        when (request) {
            is ElevatorRequest.Internal -> {
                if (floor > currentFloor) upRequests.add(floor)
                else if (floor < currentFloor) downRequests.add(floor)
            }
            is ElevatorRequest.External -> {
                when (request.direction) {
                    Direction.UP -> upRequests.add(floor)
                    Direction.DOWN -> downRequests.add(floor)
                    Direction.IDLE -> {
                        if (floor > currentFloor) upRequests.add(floor)
                        else downRequests.add(floor)
                    }
                }
            }
        }
    }
    
    override fun emergencyStop(reason: String) {
        transition(ElevatorState.EMERGENCY_STOP)
        notifyObservers(ElevatorEvent.EmergencyStop(id, reason))
    }
    
    override fun setMaintenanceMode(enabled: Boolean) {
        maintenanceMode = enabled
        if (enabled && state == ElevatorState.IDLE) {
            transition(ElevatorState.MAINTENANCE)
        } else if (!enabled && state == ElevatorState.MAINTENANCE) {
            transition(ElevatorState.IDLE)
        }
        notifyObservers(ElevatorEvent.MaintenanceMode(id, enabled))
    }
    
    fun setCurrentLoad(load: Int) {
        currentLoad = load
    }
    
    override fun step(): ElevatorEvent? {
        // Check overweight
        val weight = sensor.getCurrentWeight()
        if (weight > maxCapacity && state == ElevatorState.DOORS_OPEN) {
            notifyObservers(ElevatorEvent.OverweightDetected(id, weight))
            return ElevatorEvent.OverweightDetected(id, weight)
        }
        
        return when (state) {
            ElevatorState.IDLE -> stepIdle()
            ElevatorState.MOVING_UP -> stepMovingUp()
            ElevatorState.MOVING_DOWN -> stepMovingDown()
            ElevatorState.STOPPED -> stepStopped()
            ElevatorState.DOORS_OPENING -> stepDoorsOpening()
            ElevatorState.DOORS_OPEN -> stepDoorsOpen()
            ElevatorState.DOORS_CLOSING -> stepDoorsClosing()
            ElevatorState.EMERGENCY_STOP -> null
            ElevatorState.MAINTENANCE -> null
        }
    }
    
    private fun stepIdle(): ElevatorEvent? {
        if (maintenanceMode) {
            transition(ElevatorState.MAINTENANCE)
            return null
        }
        
        // Check if current floor has a request
        if (upRequests.contains(currentFloor) || downRequests.contains(currentFloor)) {
            upRequests.remove(currentFloor)
            downRequests.remove(currentFloor)
            transition(ElevatorState.DOORS_OPENING)
            return null
        }
        
        // Decide direction based on requests
        if (upRequests.isNotEmpty()) {
            direction = Direction.UP
            transition(ElevatorState.MOVING_UP)
        } else if (downRequests.isNotEmpty()) {
            direction = Direction.DOWN
            transition(ElevatorState.MOVING_DOWN)
        }
        return null
    }
    
    private fun stepMovingUp(): ElevatorEvent? {
        currentFloor++
        val event = ElevatorEvent.FloorReached(id, currentFloor)
        notifyObservers(event)
        
        // Check if we should stop at this floor
        if (shouldStopAt(currentFloor, Direction.UP)) {
            transition(ElevatorState.STOPPED)
        } else if (currentFloor >= maxFloor) {
            transition(ElevatorState.STOPPED)
        }
        
        return event
    }
    
    private fun stepMovingDown(): ElevatorEvent? {
        currentFloor--
        val event = ElevatorEvent.FloorReached(id, currentFloor)
        notifyObservers(event)
        
        // Check if we should stop at this floor
        if (shouldStopAt(currentFloor, Direction.DOWN)) {
            transition(ElevatorState.STOPPED)
        } else if (currentFloor <= minFloor) {
            transition(ElevatorState.STOPPED)
        }
        
        return event
    }
    
    private fun shouldStopAt(floor: Int, dir: Direction): Boolean {
        return when (dir) {
            Direction.UP -> upRequests.contains(floor)
            Direction.DOWN -> downRequests.contains(floor)
            Direction.IDLE -> upRequests.contains(floor) || downRequests.contains(floor)
        }
    }
    
    private fun stepStopped(): ElevatorEvent? {
        // Remove current floor from requests
        upRequests.remove(currentFloor)
        downRequests.remove(currentFloor)
        
        transition(ElevatorState.DOORS_OPENING)
        return null
    }
    
    private fun stepDoorsOpening(): ElevatorEvent? {
        transition(ElevatorState.DOORS_OPEN)
        doorTimer = doorOpenDuration
        val event = ElevatorEvent.DoorsOpened(id)
        notifyObservers(event)
        return event
    }
    
    private fun stepDoorsOpen(): ElevatorEvent? {
        doorTimer--
        if (doorTimer <= 0) {
            transition(ElevatorState.DOORS_CLOSING)
        }
        return null
    }
    
    private fun stepDoorsClosing(): ElevatorEvent? {
        if (sensor.isDoorBlocked()) {
            transition(ElevatorState.DOORS_OPENING)
            val event = ElevatorEvent.DoorBlocked(id)
            notifyObservers(event)
            return event
        }
        
        val event = ElevatorEvent.DoorsClosed(id)
        notifyObservers(event)
        
        // Decide next action
        when {
            direction == Direction.UP && upRequests.isNotEmpty() -> {
                transition(ElevatorState.MOVING_UP)
            }
            direction == Direction.DOWN && downRequests.isNotEmpty() -> {
                transition(ElevatorState.MOVING_DOWN)
            }
            upRequests.isNotEmpty() -> {
                direction = Direction.UP
                transition(ElevatorState.MOVING_UP)
            }
            downRequests.isNotEmpty() -> {
                direction = Direction.DOWN
                transition(ElevatorState.MOVING_DOWN)
            }
            else -> {
                direction = Direction.IDLE
                transition(ElevatorState.IDLE)
            }
        }
        
        return event
    }
    
    override fun addObserver(observer: ElevatorObserver) {
        observers.add(observer)
    }
    
    override fun removeObserver(observer: ElevatorObserver) {
        observers.remove(observer)
    }
    
    private fun notifyObservers(event: ElevatorEvent) {
        observers.forEach { it.onEvent(event) }
    }
}

/**
 * Controller for a building with multiple state machine elevators
 */
class StateMachineController(
    private val elevators: List<StateMachineElevator>,
    private val dispatchStrategy: DispatchStrategy = FCFSDispatchStrategy()
) : ElevatorController {
    
    override fun requestElevator(floor: Int, direction: Direction) {
        val request = ElevatorRequest.External(floor, direction)
        val elevator = dispatchStrategy.selectElevator(request, elevators)
        elevator?.addRequest(request)
    }
    
    override fun requestFloor(elevatorId: String, floor: Int) {
        elevators.find { it.id == elevatorId }?.addRequest(
            ElevatorRequest.Internal(floor)
        )
    }
    
    override fun getElevatorStatus(elevatorId: String): ElevatorStatus? {
        return elevators.find { it.id == elevatorId }?.getStatus()
    }
    
    override fun getAllStatus(): List<ElevatorStatus> {
        return elevators.map { it.getStatus() }
    }
    
    override fun emergencyStopAll(reason: String) {
        elevators.forEach { it.emergencyStop(reason) }
    }
    
    fun stepAll(): List<ElevatorEvent?> {
        return elevators.map { it.step() }
    }
}

/** First-Come-First-Served dispatch strategy */
class FCFSDispatchStrategy : DispatchStrategy {
    override fun selectElevator(
        request: ElevatorRequest.External,
        elevators: List<Elevator>
    ): Elevator? {
        return elevators
            .filter { it.getStatus().state != ElevatorState.MAINTENANCE }
            .filter { it.getStatus().state != ElevatorState.EMERGENCY_STOP }
            .minByOrNull { 
                kotlin.math.abs(it.getStatus().currentFloor - request.floor)
            }
    }
}
