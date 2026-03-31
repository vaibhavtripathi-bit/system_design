package com.systemdesign.trafficlight

import com.systemdesign.trafficlight.common.*
import com.systemdesign.trafficlight.approach_01_state_machine.*
import com.systemdesign.trafficlight.approach_02_strategy_timing.*
import com.systemdesign.trafficlight.approach_03_observer_coordination.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested

class TrafficLightTest {
    @Nested
    inner class SingleLightTest {
        private lateinit var light: SingleLightStateMachine
        
        @BeforeEach
        fun setup() {
            light = SingleLightStateMachine(TrafficLight("L1", Direction.NORTH_SOUTH))
        }
        
        @Test
        fun `starts in RED`() = assertEquals(LightState.RED, light.getState())
        
        @Test
        fun `transitions RED to GREEN`() {
            assertTrue(light.transition(LightState.GREEN))
            assertEquals(LightState.GREEN, light.getState())
        }
        
        @Test
        fun `transitions GREEN to YELLOW`() {
            light.transition(LightState.GREEN)
            assertTrue(light.transition(LightState.YELLOW))
        }
        
        @Test
        fun `invalid transition fails`() {
            assertFalse(light.transition(LightState.YELLOW))
        }
        
        @Test
        fun `forceRed works from any state`() {
            light.transition(LightState.GREEN)
            light.forceRed()
            assertEquals(LightState.RED, light.getState())
        }
    }
    
    @Nested
    inner class IntersectionTest {
        private lateinit var controller: IntersectionController
        private lateinit var sensor: DefaultTrafficSensor
        
        @BeforeEach
        fun setup() {
            sensor = DefaultTrafficSensor()
            controller = IntersectionController(
                SingleLightStateMachine(TrafficLight("NS", Direction.NORTH_SOUTH)),
                SingleLightStateMachine(TrafficLight("EW", Direction.EAST_WEST)),
                sensor
            )
        }
        
        @Test
        fun `all lights start RED`() {
            val states = controller.getCurrentStates()
            assertEquals(LightState.RED, states[Direction.NORTH_SOUTH])
            assertEquals(LightState.RED, states[Direction.EAST_WEST])
        }
        
        @Test
        fun `cycleNorthSouth turns NS green`() {
            controller.cycleNorthSouth()
            assertEquals(LightState.GREEN, controller.getCurrentStates()[Direction.NORTH_SOUTH])
        }
        
        @Test
        fun `emergency vehicle triggers preemption`() {
            var preempted = false
            controller.addObserver(object : TrafficLightObserver {
                override fun onStateChange(light: TrafficLight, oldState: LightState, newState: LightState) {}
                override fun onEmergencyPreemption(vehicle: EmergencyVehicle) { preempted = true }
            })
            sensor.setEmergencyVehicle(EmergencyVehicle("E1", Direction.NORTH_SOUTH))
            controller.step()
            assertTrue(preempted)
        }
    }
    
    @Nested
    inner class TimingStrategyTest {
        private val sensor = DefaultTrafficSensor()
        
        @Test
        fun `fixed timing returns constant`() {
            val strategy = FixedTimingStrategy(30000)
            assertEquals(30000, strategy.calculateGreenDuration(Direction.NORTH_SOUTH, sensor))
        }
        
        @Test
        fun `adaptive timing increases with traffic`() {
            val strategy = AdaptiveTimingStrategy(15000, 60000, 2000)
            sensor.setVehicleCount(Direction.NORTH_SOUTH, 10)
            assertEquals(35000, strategy.calculateGreenDuration(Direction.NORTH_SOUTH, sensor))
        }
        
        @Test
        fun `adaptive timing respects max`() {
            val strategy = AdaptiveTimingStrategy(15000, 60000, 2000)
            sensor.setVehicleCount(Direction.NORTH_SOUTH, 100)
            assertEquals(60000, strategy.calculateGreenDuration(Direction.NORTH_SOUTH, sensor))
        }
    }
    
    @Nested
    inner class ObserverCoordinationTest {
        
        private fun createIntersection(id: String) = CoordinatedIntersection(
            id = id,
            nsLight = TrafficLight("${id}_NS", Direction.NORTH_SOUTH),
            ewLight = TrafficLight("${id}_EW", Direction.EAST_WEST)
        )
        
        @Test
        fun `intersection starts in ALL_RED`() {
            val intersection = createIntersection("I1")
            assertEquals(IntersectionPhase.ALL_RED_EW_TO_NS, intersection.getCurrentPhase())
        }
        
        @Test
        fun `advance phase cycles through all phases`() {
            val intersection = createIntersection("I1")
            intersection.advancePhase()
            assertEquals(IntersectionPhase.NS_GREEN, intersection.getCurrentPhase())
            intersection.advancePhase()
            assertEquals(IntersectionPhase.NS_YELLOW, intersection.getCurrentPhase())
            intersection.advancePhase()
            assertEquals(IntersectionPhase.ALL_RED_NS_TO_EW, intersection.getCurrentPhase())
        }
        
        @Test
        fun `emergency override forces lights`() {
            val intersection = createIntersection("I1")
            intersection.setPhase(IntersectionPhase.EW_GREEN)
            
            intersection.forceEmergency(EmergencyVehicle("E1", Direction.NORTH_SOUTH))
            
            val lights = intersection.getLightStates()
            assertEquals(LightState.GREEN, lights[Direction.NORTH_SOUTH])
            assertEquals(LightState.RED, lights[Direction.EAST_WEST])
        }
        
        @Test
        fun `observer receives phase changes`() {
            val intersection = createIntersection("I1")
            val changes = mutableListOf<PhaseChange>()
            intersection.addObserver(object : IntersectionObserver {
                override fun onPhaseChange(change: PhaseChange) { changes.add(change) }
                override fun onEmergencyOverride(intersectionId: String, vehicle: EmergencyVehicle) {}
            })
            
            intersection.advancePhase()
            
            assertEquals(1, changes.size)
            assertEquals(IntersectionPhase.NS_GREEN, changes[0].newPhase)
        }
        
        @Test
        fun `traffic coordinator manages network`() {
            val coordinator = TrafficCoordinator()
            val i1 = createIntersection("I1")
            val i2 = createIntersection("I2")
            
            coordinator.addIntersection(i1)
            coordinator.addIntersection(i2)
            
            val status = coordinator.getNetworkStatus()
            assertEquals(2, status.size)
        }
        
        @Test
        fun `emergency cascades through corridor`() {
            val coordinator = TrafficCoordinator()
            val i1 = createIntersection("I1")
            val i2 = createIntersection("I2")
            coordinator.addIntersection(i1)
            coordinator.addIntersection(i2)
            coordinator.defineCorridor(CorridorConfig("Main", listOf("I1", "I2"), Direction.NORTH_SOUTH, 5000))
            
            coordinator.triggerEmergency("I1", EmergencyVehicle("E1", Direction.NORTH_SOUTH))
            
            val i2Lights = i2.getLightStates()
            assertEquals(LightState.GREEN, i2Lights[Direction.NORTH_SOUTH])
        }
    }
}
