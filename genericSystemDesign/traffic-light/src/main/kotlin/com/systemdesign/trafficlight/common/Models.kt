package com.systemdesign.trafficlight.common

enum class LightState { RED, YELLOW, GREEN }
enum class Direction { NORTH_SOUTH, EAST_WEST, PEDESTRIAN }

data class LightTiming(val green: Long, val yellow: Long, val red: Long)
data class TrafficLight(val id: String, val direction: Direction, var state: LightState = LightState.RED)
data class EmergencyVehicle(val id: String, val direction: Direction)

interface TrafficSensor {
    fun getVehicleCount(direction: Direction): Int
    fun detectEmergencyVehicle(): EmergencyVehicle?
}

interface TrafficLightObserver {
    fun onStateChange(light: TrafficLight, oldState: LightState, newState: LightState)
    fun onEmergencyPreemption(vehicle: EmergencyVehicle)
}

class DefaultTrafficSensor : TrafficSensor {
    private val counts = mutableMapOf<Direction, Int>()
    private var emergency: EmergencyVehicle? = null
    
    fun setVehicleCount(direction: Direction, count: Int) { counts[direction] = count }
    fun setEmergencyVehicle(vehicle: EmergencyVehicle?) { emergency = vehicle }
    override fun getVehicleCount(direction: Direction) = counts[direction] ?: 0
    override fun detectEmergencyVehicle() = emergency
}
