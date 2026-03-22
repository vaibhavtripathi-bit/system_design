package com.systemdesign.elevator.approach_03_command_pattern

import com.systemdesign.elevator.common.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Approach 3: Command Pattern
 * 
 * Floor requests are modeled as Command objects that can be queued, 
 * prioritized, cancelled, and logged. Enables rich request lifecycle management.
 * 
 * Pattern: Command Pattern
 * 
 * Trade-offs:
 * + Requests can be queued, prioritized, cancelled
 * + Full request lifecycle tracking and logging
 * + Supports undo/redo for certain operations
 * + Decouples request creation from execution
 * - More memory overhead per request
 * - Command objects add indirection
 * 
 * When to use:
 * - When request lifecycle tracking is important
 * - When requests need to be cancelled/modified
 * - When request history/audit trail is needed
 * - When implementing request prioritization
 * 
 * Extensibility:
 * - New command type: Implement ElevatorCommand interface
 * - Request priority: Add priority field to commands
 * - Request cancellation: Track command state and filter on execution
 */

/** Command interface for elevator operations */
sealed interface ElevatorCommand {
    val id: String
    val timestamp: Long
    val priority: Int
    fun execute(elevator: CommandElevator): CommandResult
}

/** Result of command execution */
sealed class CommandResult {
    data class Success(val message: String) : CommandResult()
    data class Failure(val reason: String) : CommandResult()
    data class Queued(val position: Int) : CommandResult()
    data object Cancelled : CommandResult()
}

/** Command state for lifecycle tracking */
enum class CommandState {
    PENDING,
    QUEUED,
    EXECUTING,
    COMPLETED,
    CANCELLED,
    FAILED
}

/** Go to floor command */
data class GoToFloorCommand(
    override val id: String = java.util.UUID.randomUUID().toString(),
    override val timestamp: Long = System.currentTimeMillis(),
    override val priority: Int = 0,
    val targetFloor: Int,
    val fromCabin: Boolean = true,
    var state: CommandState = CommandState.PENDING
) : ElevatorCommand {
    
    override fun execute(elevator: CommandElevator): CommandResult {
        if (state == CommandState.CANCELLED) {
            return CommandResult.Cancelled
        }
        
        if (targetFloor < elevator.minFloor || targetFloor > elevator.maxFloor) {
            state = CommandState.FAILED
            return CommandResult.Failure("Invalid floor: $targetFloor")
        }
        
        state = CommandState.QUEUED
        elevator.queueFloorRequest(this)
        return CommandResult.Queued(elevator.getQueuePosition(this))
    }
}

/** Emergency stop command */
data class EmergencyStopCommand(
    override val id: String = java.util.UUID.randomUUID().toString(),
    override val timestamp: Long = System.currentTimeMillis(),
    override val priority: Int = Int.MAX_VALUE,
    val reason: String
) : ElevatorCommand {
    
    override fun execute(elevator: CommandElevator): CommandResult {
        elevator.triggerEmergencyStop(reason)
        return CommandResult.Success("Emergency stop triggered: $reason")
    }
}

/** Open doors command */
data class OpenDoorsCommand(
    override val id: String = java.util.UUID.randomUUID().toString(),
    override val timestamp: Long = System.currentTimeMillis(),
    override val priority: Int = 5
) : ElevatorCommand {
    
    override fun execute(elevator: CommandElevator): CommandResult {
        return if (elevator.canOpenDoors()) {
            elevator.openDoors()
            CommandResult.Success("Doors opening")
        } else {
            CommandResult.Failure("Cannot open doors while moving")
        }
    }
}

/** Close doors command */
data class CloseDoorsCommand(
    override val id: String = java.util.UUID.randomUUID().toString(),
    override val timestamp: Long = System.currentTimeMillis(),
    override val priority: Int = 5
) : ElevatorCommand {
    
    override fun execute(elevator: CommandElevator): CommandResult {
        return if (elevator.canCloseDoors()) {
            elevator.closeDoors()
            CommandResult.Success("Doors closing")
        } else {
            CommandResult.Failure("Cannot close doors: blocked or already closed")
        }
    }
}

/** Command-based elevator implementation */
class CommandElevator(
    override val id: String,
    override val minFloor: Int = 0,
    override val maxFloor: Int = 10,
    override val maxCapacity: Int = 1000,
    private val sensor: ElevatorSensor = DefaultElevatorSensor()
) : Elevator {
    
    private var currentFloor: Int = 0
    private var state: ElevatorState = ElevatorState.IDLE
    private var direction: Direction = Direction.IDLE
    
    private val commandQueue = ConcurrentLinkedQueue<GoToFloorCommand>()
    private val commandHistory = mutableListOf<ElevatorCommand>()
    private val observers = CopyOnWriteArrayList<ElevatorObserver>()
    
    private val upRequests = sortedSetOf<Int>()
    private val downRequests = sortedSetOf<Int>(Comparator.reverseOrder())
    
    fun submitCommand(command: ElevatorCommand): CommandResult {
        commandHistory.add(command)
        return command.execute(this)
    }
    
    fun queueFloorRequest(command: GoToFloorCommand) {
        commandQueue.add(command)
        val floor = command.targetFloor
        if (floor > currentFloor) {
            upRequests.add(floor)
        } else if (floor < currentFloor) {
            downRequests.add(floor)
        }
    }
    
    fun getQueuePosition(command: GoToFloorCommand): Int {
        return commandQueue.indexOf(command)
    }
    
    fun cancelCommand(commandId: String): Boolean {
        val command = commandQueue.find { it.id == commandId }
        if (command != null) {
            command.state = CommandState.CANCELLED
            commandQueue.remove(command)
            upRequests.remove(command.targetFloor)
            downRequests.remove(command.targetFloor)
            return true
        }
        return false
    }
    
    fun canOpenDoors(): Boolean {
        return state != ElevatorState.MOVING_UP && 
               state != ElevatorState.MOVING_DOWN
    }
    
    fun canCloseDoors(): Boolean {
        return state == ElevatorState.DOORS_OPEN && !sensor.isDoorBlocked()
    }
    
    fun openDoors() {
        state = ElevatorState.DOORS_OPEN
        notifyObservers(ElevatorEvent.DoorsOpened(id))
    }
    
    fun closeDoors() {
        if (!sensor.isDoorBlocked()) {
            state = ElevatorState.IDLE
            notifyObservers(ElevatorEvent.DoorsClosed(id))
        }
    }
    
    fun triggerEmergencyStop(reason: String) {
        state = ElevatorState.EMERGENCY_STOP
        notifyObservers(ElevatorEvent.EmergencyStop(id, reason))
    }
    
    fun getCommandHistory(): List<ElevatorCommand> = commandHistory.toList()
    
    fun getPendingCommands(): List<GoToFloorCommand> = commandQueue.toList()
    
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
            currentLoad = sensor.getCurrentWeight(),
            maxCapacity = maxCapacity
        )
    }
    
    override fun addRequest(request: ElevatorRequest) {
        val command = GoToFloorCommand(
            targetFloor = request.floor,
            fromCabin = request is ElevatorRequest.Internal
        )
        submitCommand(command)
    }
    
    override fun emergencyStop(reason: String) {
        submitCommand(EmergencyStopCommand(reason = reason))
    }
    
    override fun setMaintenanceMode(enabled: Boolean) {
        state = if (enabled) ElevatorState.MAINTENANCE else ElevatorState.IDLE
        notifyObservers(ElevatorEvent.MaintenanceMode(id, enabled))
    }
    
    override fun step(): ElevatorEvent? {
        if (state == ElevatorState.EMERGENCY_STOP || state == ElevatorState.MAINTENANCE) {
            return null
        }
        
        // Mark completed commands
        commandQueue.removeIf { command ->
            if (command.targetFloor == currentFloor) {
                command.state = CommandState.COMPLETED
                true
            } else false
        }
        
        // Process movement
        return when {
            currentFloor in upRequests -> {
                upRequests.remove(currentFloor)
                state = ElevatorState.DOORS_OPEN
                ElevatorEvent.DoorsOpened(id).also { notifyObservers(it) }
            }
            currentFloor in downRequests -> {
                downRequests.remove(currentFloor)
                state = ElevatorState.DOORS_OPEN
                ElevatorEvent.DoorsOpened(id).also { notifyObservers(it) }
            }
            direction == Direction.UP && upRequests.isNotEmpty() -> {
                currentFloor++
                state = ElevatorState.MOVING_UP
                ElevatorEvent.FloorReached(id, currentFloor).also { notifyObservers(it) }
            }
            direction == Direction.DOWN && downRequests.isNotEmpty() -> {
                currentFloor--
                state = ElevatorState.MOVING_DOWN
                ElevatorEvent.FloorReached(id, currentFloor).also { notifyObservers(it) }
            }
            upRequests.isNotEmpty() -> {
                direction = Direction.UP
                currentFloor++
                state = ElevatorState.MOVING_UP
                ElevatorEvent.FloorReached(id, currentFloor).also { notifyObservers(it) }
            }
            downRequests.isNotEmpty() -> {
                direction = Direction.DOWN
                currentFloor--
                state = ElevatorState.MOVING_DOWN
                ElevatorEvent.FloorReached(id, currentFloor).also { notifyObservers(it) }
            }
            else -> {
                direction = Direction.IDLE
                state = ElevatorState.IDLE
                null
            }
        }
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
