package com.systemdesign.elevator

import com.systemdesign.elevator.common.*
import com.systemdesign.elevator.approach_01_state_machine.*
import com.systemdesign.elevator.approach_02_strategy_dispatch.*
import com.systemdesign.elevator.approach_03_command_pattern.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested

class ElevatorSystemTest {
    
    @Nested
    inner class StateMachineElevatorTest {
        
        private lateinit var elevator: StateMachineElevator
        private lateinit var sensor: DefaultElevatorSensor
        private val events = mutableListOf<ElevatorEvent>()
        
        @BeforeEach
        fun setup() {
            sensor = DefaultElevatorSensor()
            elevator = StateMachineElevator(
                id = "E1",
                minFloor = 0,
                maxFloor = 10,
                maxCapacity = 1000,
                sensor = sensor,
                doorOpenDuration = 1
            )
            elevator.addObserver(object : ElevatorObserver {
                override fun onEvent(event: ElevatorEvent) {
                    events.add(event)
                }
            })
            events.clear()
        }
        
        @Test
        fun `starts in idle state at floor 0`() {
            val status = elevator.getStatus()
            assertEquals(ElevatorState.IDLE, status.state)
            assertEquals(0, status.currentFloor)
            assertEquals(Direction.IDLE, status.direction)
        }
        
        @Test
        fun `moves up to requested floor`() {
            elevator.addRequest(ElevatorRequest.Internal(3))
            
            repeat(5) { elevator.step() }
            
            val status = elevator.getStatus()
            assertEquals(3, status.currentFloor)
        }
        
        @Test
        fun `moves down to requested floor`() {
            // First move to floor 5
            elevator.addRequest(ElevatorRequest.Internal(5))
            repeat(10) { elevator.step() }
            
            // Then request floor 2
            elevator.addRequest(ElevatorRequest.Internal(2))
            repeat(10) { elevator.step() }
            
            val status = elevator.getStatus()
            assertEquals(2, status.currentFloor)
        }
        
        @Test
        fun `doors blocked - reopens doors`() {
            elevator.addRequest(ElevatorRequest.Internal(1))
            
            // Move to floor and open doors
            // Step 1: IDLE -> MOVING_UP (moves to floor 1)
            // Step 2: MOVING_UP -> STOPPED (at floor 1)
            // Step 3: STOPPED -> DOORS_OPENING
            // Step 4: DOORS_OPENING -> DOORS_OPEN (timer=1)
            // Step 5: DOORS_OPEN -> DOORS_CLOSING (timer decremented to 0)
            repeat(5) { elevator.step() }
            
            // Now in DOORS_CLOSING state - block door
            sensor.setDoorBlocked(true)
            
            val event = elevator.step()
            assertTrue(event is ElevatorEvent.DoorBlocked)
        }
        
        @Test
        fun `emergency stop halts elevator`() {
            elevator.addRequest(ElevatorRequest.Internal(5))
            elevator.step()
            
            elevator.emergencyStop("Fire alarm")
            
            val status = elevator.getStatus()
            assertEquals(ElevatorState.EMERGENCY_STOP, status.state)
            
            // Should not move after emergency stop
            elevator.step()
            assertEquals(ElevatorState.EMERGENCY_STOP, elevator.getStatus().state)
        }
        
        @Test
        fun `maintenance mode prevents new requests`() {
            elevator.setMaintenanceMode(true)
            elevator.addRequest(ElevatorRequest.Internal(5))
            
            val status = elevator.getStatus()
            assertTrue(status.pendingRequests.isEmpty())
        }
        
        @Test
        fun `overweight detected triggers event`() {
            // Move to floor and open doors
            elevator.addRequest(ElevatorRequest.Internal(1))
            // Step through to DOORS_OPEN state
            // Step 1: IDLE -> MOVING_UP
            // Step 2: MOVING_UP -> STOPPED
            // Step 3: STOPPED -> DOORS_OPENING
            // Step 4: DOORS_OPENING -> DOORS_OPEN
            repeat(4) { elevator.step() }
            
            // Now in DOORS_OPEN state - set overweight
            sensor.setCurrentWeight(1500)
            
            val event = elevator.step()
            assertTrue(event is ElevatorEvent.OverweightDetected)
        }
        
        @Test
        fun `serves multiple requests in same direction efficiently`() {
            elevator.addRequest(ElevatorRequest.Internal(2))
            elevator.addRequest(ElevatorRequest.Internal(5))
            elevator.addRequest(ElevatorRequest.Internal(3))
            
            // Should stop at 2, 3, 5 in order
            val stoppedFloors = mutableListOf<Int>()
            repeat(20) { 
                val event = elevator.step()
                if (event is ElevatorEvent.DoorsOpened) {
                    stoppedFloors.add(elevator.getStatus().currentFloor)
                }
            }
            
            assertEquals(listOf(2, 3, 5), stoppedFloors)
        }
        
        @Test
        fun `rejects requests outside valid floor range`() {
            elevator.addRequest(ElevatorRequest.Internal(-1))
            elevator.addRequest(ElevatorRequest.Internal(15))
            
            val status = elevator.getStatus()
            assertTrue(status.pendingRequests.isEmpty())
        }
    }
    
    @Nested
    inner class DispatchStrategyTest {
        
        private lateinit var elevators: List<StateMachineElevator>
        
        @BeforeEach
        fun setup() {
            elevators = listOf(
                StateMachineElevator("E1", 0, 10, 1000),
                StateMachineElevator("E2", 0, 10, 1000),
                StateMachineElevator("E3", 0, 10, 1000)
            )
            // Move E2 to floor 5
            elevators[1].addRequest(ElevatorRequest.Internal(5))
            repeat(10) { elevators[1].step() }
        }
        
        @Test
        fun `FCFS strategy selects nearest idle elevator`() {
            val strategy = FCFSDispatchStrategy()
            val request = ElevatorRequest.External(4, Direction.UP)
            
            val selected = strategy.selectElevator(request, elevators)
            
            assertEquals("E2", selected?.id)
        }
        
        @Test
        fun `SCAN strategy prefers elevator moving in same direction`() {
            val strategy = ScanDispatchStrategy()
            
            // E1 at floor 0, going up
            elevators[0].addRequest(ElevatorRequest.Internal(8))
            elevators[0].step() // Now moving up
            
            val request = ElevatorRequest.External(3, Direction.UP)
            val selected = strategy.selectElevator(request, elevators)
            
            // E1 should be selected since it's moving up and will pass floor 3
            assertEquals("E1", selected?.id)
        }
        
        @Test
        fun `LOOK strategy calculates efficient paths`() {
            val strategy = LookDispatchStrategy()
            
            // E1 going up with pending requests
            elevators[0].addRequest(ElevatorRequest.Internal(8))
            elevators[0].step()
            
            val request = ElevatorRequest.External(7, Direction.UP)
            val selected = strategy.selectElevator(request, elevators)
            
            assertNotNull(selected)
        }
        
        @Test
        fun `load balanced strategy selects elevator with fewest requests`() {
            val strategy = LoadBalancedStrategy()
            
            // Add many requests to E1
            repeat(5) { i ->
                elevators[0].addRequest(ElevatorRequest.Internal(i + 1))
            }
            
            val request = ElevatorRequest.External(3, Direction.UP)
            val selected = strategy.selectElevator(request, elevators)
            
            // Should NOT select E1 (has most requests)
            assertNotEquals("E1", selected?.id)
        }
        
        @Test
        fun `excludes elevators in maintenance mode`() {
            val strategy = FCFSDispatchStrategy()
            
            // Move E2 to idle state first by completing its movement
            repeat(10) { elevators[1].step() }
            elevators[1].setMaintenanceMode(true)
            
            val request = ElevatorRequest.External(5, Direction.UP)
            val selected = strategy.selectElevator(request, elevators)
            
            // E2 is closest to floor 5 but in maintenance, so E1 or E3 should be selected
            assertTrue(selected?.id == "E1" || selected?.id == "E3")
        }
        
        @Test
        fun `excludes elevators in emergency stop`() {
            val strategy = FCFSDispatchStrategy()
            
            elevators[1].emergencyStop("Test")
            
            val request = ElevatorRequest.External(5, Direction.UP)
            val selected = strategy.selectElevator(request, elevators)
            
            assertNotEquals("E2", selected?.id)
        }
    }
    
    @Nested
    inner class CommandPatternTest {
        
        private lateinit var elevator: CommandElevator
        
        @BeforeEach
        fun setup() {
            elevator = CommandElevator(
                id = "E1",
                minFloor = 0,
                maxFloor = 10,
                maxCapacity = 1000
            )
        }
        
        @Test
        fun `go to floor command queues request`() {
            val command = GoToFloorCommand(targetFloor = 5)
            val result = elevator.submitCommand(command)
            
            assertTrue(result is CommandResult.Queued)
            assertEquals(CommandState.QUEUED, command.state)
        }
        
        @Test
        fun `invalid floor command fails`() {
            val command = GoToFloorCommand(targetFloor = 15)
            val result = elevator.submitCommand(command)
            
            assertTrue(result is CommandResult.Failure)
            assertEquals(CommandState.FAILED, command.state)
        }
        
        @Test
        fun `cancelled command returns cancelled result`() {
            val command = GoToFloorCommand(targetFloor = 5)
            command.state = CommandState.CANCELLED
            
            val result = elevator.submitCommand(command)
            
            assertTrue(result is CommandResult.Cancelled)
        }
        
        @Test
        fun `command can be cancelled after queueing`() {
            val command = GoToFloorCommand(targetFloor = 5)
            elevator.submitCommand(command)
            
            val cancelled = elevator.cancelCommand(command.id)
            
            assertTrue(cancelled)
            assertEquals(CommandState.CANCELLED, command.state)
            assertTrue(elevator.getPendingCommands().isEmpty())
        }
        
        @Test
        fun `emergency stop command executes immediately`() {
            val command = EmergencyStopCommand(reason = "Fire")
            val result = elevator.submitCommand(command)
            
            assertTrue(result is CommandResult.Success)
            assertEquals(ElevatorState.EMERGENCY_STOP, elevator.getStatus().state)
        }
        
        @Test
        fun `command history tracks all commands`() {
            elevator.submitCommand(GoToFloorCommand(targetFloor = 3))
            elevator.submitCommand(GoToFloorCommand(targetFloor = 5))
            elevator.submitCommand(OpenDoorsCommand())
            
            val history = elevator.getCommandHistory()
            assertEquals(3, history.size)
        }
        
        @Test
        fun `open doors fails while moving`() {
            elevator.submitCommand(GoToFloorCommand(targetFloor = 5))
            elevator.step() // Start moving
            
            val result = elevator.submitCommand(OpenDoorsCommand())
            
            assertTrue(result is CommandResult.Failure)
        }
        
        @Test
        fun `commands are completed when floor is reached`() {
            val command = GoToFloorCommand(targetFloor = 1)
            elevator.submitCommand(command)
            
            // Step until floor reached
            repeat(5) { elevator.step() }
            
            assertEquals(CommandState.COMPLETED, command.state)
        }
    }
    
    @Nested
    inner class ControllerTest {
        
        @Test
        fun `controller dispatches to multiple elevators`() {
            val elevators = listOf(
                StateMachineElevator("E1", 0, 10, 1000),
                StateMachineElevator("E2", 0, 10, 1000)
            )
            val controller = StateMachineController(elevators)
            
            controller.requestElevator(5, Direction.UP)
            controller.requestElevator(3, Direction.UP)
            
            val allStatus = controller.getAllStatus()
            assertEquals(2, allStatus.size)
        }
        
        @Test
        fun `controller emergency stops all elevators`() {
            val elevators = listOf(
                StateMachineElevator("E1", 0, 10, 1000),
                StateMachineElevator("E2", 0, 10, 1000)
            )
            val controller = StateMachineController(elevators)
            
            controller.emergencyStopAll("Fire alarm")
            
            val allStatus = controller.getAllStatus()
            assertTrue(allStatus.all { it.state == ElevatorState.EMERGENCY_STOP })
        }
        
        @Test
        fun `strategy controller allows runtime strategy change`() {
            val elevators = listOf(
                StateMachineElevator("E1", 0, 10, 1000),
                StateMachineElevator("E2", 0, 10, 1000)
            )
            val controller = StrategyBasedController(elevators, FCFSDispatchStrategy())
            
            assertTrue(controller.getStrategy() is FCFSDispatchStrategy)
            
            controller.setStrategy(LookDispatchStrategy())
            
            assertTrue(controller.getStrategy() is LookDispatchStrategy)
        }
    }
}
