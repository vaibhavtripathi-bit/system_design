package com.systemdesign.trafficlight

import com.systemdesign.trafficlight.common.*
import com.systemdesign.trafficlight.approach_01_state_machine.*
import com.systemdesign.trafficlight.approach_02_strategy_timing.*
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
}
