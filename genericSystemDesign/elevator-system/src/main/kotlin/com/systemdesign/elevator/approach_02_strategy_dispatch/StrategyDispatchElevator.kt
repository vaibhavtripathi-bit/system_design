package com.systemdesign.elevator.approach_02_strategy_dispatch

import com.systemdesign.elevator.common.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * Approach 2: Strategy Pattern for Dispatch Algorithms
 * 
 * Focuses on the dispatch algorithm selection using the Strategy pattern.
 * Different algorithms (FCFS, SCAN, LOOK) can be swapped at runtime.
 * 
 * Pattern: Strategy Pattern
 * 
 * Trade-offs:
 * + Algorithms are interchangeable at runtime
 * + Easy to add new dispatch algorithms without touching elevator logic
 * + Algorithms can be tested in isolation
 * - Dispatch strategy needs full system visibility
 * - May require more coordination overhead
 * 
 * When to use:
 * - When different dispatch policies are needed (peak hours, night mode)
 * - When A/B testing different algorithms
 * - When algorithm selection depends on building characteristics
 * 
 * Extensibility:
 * - New dispatch algorithm: Implement DispatchStrategy interface
 * - VIP prioritization: Create PriorityDispatchStrategy decorator
 */

/** SCAN (Elevator) Algorithm - serves all requests in one direction, then reverses */
class ScanDispatchStrategy : DispatchStrategy {
    override fun selectElevator(
        request: ElevatorRequest.External,
        elevators: List<Elevator>
    ): Elevator? {
        val availableElevators = elevators.filter { 
            val status = it.getStatus()
            status.state != ElevatorState.MAINTENANCE && 
            status.state != ElevatorState.EMERGENCY_STOP 
        }
        
        if (availableElevators.isEmpty()) return null
        
        // Prefer elevators moving towards the request floor
        val requestFloor = request.floor
        val requestDirection = request.direction
        
        // First priority: elevator moving towards request in same direction
        val sameDirection = availableElevators.filter { elevator ->
            val status = elevator.getStatus()
            when (requestDirection) {
                Direction.UP -> {
                    status.direction == Direction.UP && status.currentFloor <= requestFloor
                }
                Direction.DOWN -> {
                    status.direction == Direction.DOWN && status.currentFloor >= requestFloor
                }
                Direction.IDLE -> true
            }
        }
        
        if (sameDirection.isNotEmpty()) {
            return sameDirection.minByOrNull { abs(it.getStatus().currentFloor - requestFloor) }
        }
        
        // Second priority: idle elevators
        val idleElevators = availableElevators.filter { 
            it.getStatus().direction == Direction.IDLE 
        }
        
        if (idleElevators.isNotEmpty()) {
            return idleElevators.minByOrNull { abs(it.getStatus().currentFloor - requestFloor) }
        }
        
        // Third priority: any available elevator (will serve after completing current direction)
        return availableElevators.minByOrNull { abs(it.getStatus().currentFloor - requestFloor) }
    }
}

/** LOOK Algorithm - like SCAN but reverses at last request, not at end of building */
class LookDispatchStrategy : DispatchStrategy {
    override fun selectElevator(
        request: ElevatorRequest.External,
        elevators: List<Elevator>
    ): Elevator? {
        val availableElevators = elevators.filter { 
            val status = it.getStatus()
            status.state != ElevatorState.MAINTENANCE && 
            status.state != ElevatorState.EMERGENCY_STOP 
        }
        
        if (availableElevators.isEmpty()) return null
        
        val requestFloor = request.floor
        
        // Score each elevator based on efficiency
        return availableElevators.minByOrNull { elevator ->
            calculateLookScore(elevator, requestFloor, request.direction)
        }
    }
    
    private fun calculateLookScore(
        elevator: Elevator, 
        requestFloor: Int, 
        requestDirection: Direction
    ): Int {
        val status = elevator.getStatus()
        val currentFloor = status.currentFloor
        val direction = status.direction
        val pendingRequests = status.pendingRequests
        
        // If idle, just distance
        if (direction == Direction.IDLE) {
            return abs(currentFloor - requestFloor)
        }
        
        // If moving towards request in same direction
        val movingTowards = when (direction) {
            Direction.UP -> currentFloor <= requestFloor
            Direction.DOWN -> currentFloor >= requestFloor
            Direction.IDLE -> true
        }
        
        val sameDirection = direction == requestDirection || requestDirection == Direction.IDLE
        
        if (movingTowards && sameDirection) {
            return abs(currentFloor - requestFloor)
        }
        
        // Will need to reverse - calculate full travel distance
        val maxPending = if (direction == Direction.UP) {
            pendingRequests.maxOfOrNull { it.floor } ?: currentFloor
        } else {
            pendingRequests.minOfOrNull { it.floor } ?: currentFloor
        }
        
        val distanceToEnd = abs(currentFloor - maxPending)
        val distanceFromEndToRequest = abs(maxPending - requestFloor)
        
        return distanceToEnd + distanceFromEndToRequest
    }
}

/** Nearest-First Strategy - always dispatch the closest elevator */
class NearestFirstStrategy : DispatchStrategy {
    override fun selectElevator(
        request: ElevatorRequest.External,
        elevators: List<Elevator>
    ): Elevator? {
        return elevators
            .filter { 
                val status = it.getStatus()
                status.state != ElevatorState.MAINTENANCE && 
                status.state != ElevatorState.EMERGENCY_STOP 
            }
            .minByOrNull { abs(it.getStatus().currentFloor - request.floor) }
    }
}

/** Load-Balanced Strategy - dispatch to elevator with fewest pending requests */
class LoadBalancedStrategy : DispatchStrategy {
    override fun selectElevator(
        request: ElevatorRequest.External,
        elevators: List<Elevator>
    ): Elevator? {
        return elevators
            .filter { 
                val status = it.getStatus()
                status.state != ElevatorState.MAINTENANCE && 
                status.state != ElevatorState.EMERGENCY_STOP 
            }
            .minByOrNull { it.getStatus().pendingRequests.size }
    }
}

/**
 * Composite strategy that combines multiple strategies with weights
 */
class WeightedCompositeStrategy(
    private val strategies: List<Pair<DispatchStrategy, Double>>
) : DispatchStrategy {
    
    override fun selectElevator(
        request: ElevatorRequest.External,
        elevators: List<Elevator>
    ): Elevator? {
        val scores = mutableMapOf<Elevator, Double>()
        
        elevators.forEach { elevator ->
            scores[elevator] = 0.0
        }
        
        strategies.forEach { (strategy, weight) ->
            val selected = strategy.selectElevator(request, elevators)
            if (selected != null) {
                scores[selected] = (scores[selected] ?: 0.0) + weight
            }
        }
        
        return scores.maxByOrNull { it.value }?.key
    }
}

/**
 * Controller that allows runtime strategy switching
 */
class StrategyBasedController(
    private val elevators: List<Elevator>,
    initialStrategy: DispatchStrategy = ScanDispatchStrategy()
) : ElevatorController {
    
    private var currentStrategy: DispatchStrategy = initialStrategy
    
    fun setStrategy(strategy: DispatchStrategy) {
        currentStrategy = strategy
    }
    
    fun getStrategy(): DispatchStrategy = currentStrategy
    
    override fun requestElevator(floor: Int, direction: Direction) {
        val request = ElevatorRequest.External(floor, direction)
        val elevator = currentStrategy.selectElevator(request, elevators)
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
}
