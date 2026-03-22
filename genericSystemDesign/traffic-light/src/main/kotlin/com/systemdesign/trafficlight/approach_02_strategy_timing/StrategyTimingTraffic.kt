package com.systemdesign.trafficlight.approach_02_strategy_timing

import com.systemdesign.trafficlight.common.*

interface TimingStrategy {
    fun calculateGreenDuration(direction: Direction, sensor: TrafficSensor): Long
}

class FixedTimingStrategy(private val greenDuration: Long = 30000) : TimingStrategy {
    override fun calculateGreenDuration(direction: Direction, sensor: TrafficSensor) = greenDuration
}

class AdaptiveTimingStrategy(
    private val minGreen: Long = 15000,
    private val maxGreen: Long = 60000,
    private val vehicleWeight: Long = 2000
) : TimingStrategy {
    override fun calculateGreenDuration(direction: Direction, sensor: TrafficSensor): Long {
        val count = sensor.getVehicleCount(direction)
        return (minGreen + count * vehicleWeight).coerceIn(minGreen, maxGreen)
    }
}

class PeakHourStrategy(
    private val peakStartHour: Int = 7,
    private val peakEndHour: Int = 9,
    private val normalStrategy: TimingStrategy = FixedTimingStrategy(30000),
    private val peakStrategy: TimingStrategy = FixedTimingStrategy(45000)
) : TimingStrategy {
    override fun calculateGreenDuration(direction: Direction, sensor: TrafficSensor): Long {
        val hour = java.time.LocalTime.now().hour
        val isPeak = hour in peakStartHour until peakEndHour
        return if (isPeak) peakStrategy.calculateGreenDuration(direction, sensor)
        else normalStrategy.calculateGreenDuration(direction, sensor)
    }
}

class StrategyBasedController(
    private val sensor: TrafficSensor,
    private var strategy: TimingStrategy = FixedTimingStrategy()
) {
    fun setStrategy(newStrategy: TimingStrategy) { strategy = newStrategy }
    fun getGreenDuration(direction: Direction) = strategy.calculateGreenDuration(direction, sensor)
}
