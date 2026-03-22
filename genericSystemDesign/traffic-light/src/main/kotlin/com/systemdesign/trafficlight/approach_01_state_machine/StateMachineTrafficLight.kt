package com.systemdesign.trafficlight.approach_01_state_machine

import com.systemdesign.trafficlight.common.*
import java.util.concurrent.CopyOnWriteArrayList

class SingleLightStateMachine(private val light: TrafficLight) {
    private val observers = CopyOnWriteArrayList<TrafficLightObserver>()
    
    fun getState() = light.state
    
    fun transition(to: LightState): Boolean {
        val validTransitions = mapOf(
            LightState.GREEN to setOf(LightState.YELLOW),
            LightState.YELLOW to setOf(LightState.RED),
            LightState.RED to setOf(LightState.GREEN)
        )
        if (validTransitions[light.state]?.contains(to) != true) return false
        val old = light.state
        light.state = to
        observers.forEach { it.onStateChange(light, old, to) }
        return true
    }
    
    fun forceRed() {
        val old = light.state
        light.state = LightState.RED
        if (old != LightState.RED) observers.forEach { it.onStateChange(light, old, LightState.RED) }
    }
    
    fun addObserver(observer: TrafficLightObserver) { observers.add(observer) }
}

class IntersectionController(
    private val nsLight: SingleLightStateMachine,
    private val ewLight: SingleLightStateMachine,
    private val sensor: TrafficSensor = DefaultTrafficSensor(),
    private val timing: LightTiming = LightTiming(30000, 5000, 35000),
    private val allRedInterval: Long = 2000
) {
    private var isEmergencyMode = false
    private val observers = CopyOnWriteArrayList<TrafficLightObserver>()
    
    fun getCurrentStates() = mapOf(Direction.NORTH_SOUTH to nsLight.getState(), Direction.EAST_WEST to ewLight.getState())
    
    fun step() {
        val emergency = sensor.detectEmergencyVehicle()
        if (emergency != null) {
            handleEmergency(emergency)
            return
        }
        if (isEmergencyMode) {
            resumeNormal()
            return
        }
    }
    
    fun cycleNorthSouth() {
        if (ewLight.getState() != LightState.RED) {
            ewLight.transition(LightState.YELLOW)
            ewLight.transition(LightState.RED)
        }
        nsLight.transition(LightState.GREEN)
    }
    
    fun cycleEastWest() {
        if (nsLight.getState() != LightState.RED) {
            nsLight.transition(LightState.YELLOW)
            nsLight.transition(LightState.RED)
        }
        ewLight.transition(LightState.GREEN)
    }
    
    fun allRed() {
        nsLight.forceRed()
        ewLight.forceRed()
    }
    
    private fun handleEmergency(vehicle: EmergencyVehicle) {
        isEmergencyMode = true
        allRed()
        when (vehicle.direction) {
            Direction.NORTH_SOUTH -> nsLight.transition(LightState.GREEN)
            Direction.EAST_WEST -> ewLight.transition(LightState.GREEN)
            else -> {}
        }
        observers.forEach { it.onEmergencyPreemption(vehicle) }
    }
    
    private fun resumeNormal() {
        isEmergencyMode = false
        allRed()
    }
    
    fun addObserver(observer: TrafficLightObserver) {
        observers.add(observer)
        nsLight.addObserver(observer)
        ewLight.addObserver(observer)
    }
}
