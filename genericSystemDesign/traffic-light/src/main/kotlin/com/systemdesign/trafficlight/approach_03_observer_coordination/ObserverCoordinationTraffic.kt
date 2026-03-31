package com.systemdesign.trafficlight.approach_03_observer_coordination

import com.systemdesign.trafficlight.common.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Approach 3: Observer Pattern for Multi-Intersection Coordination
 *
 * Intersections observe each other to create "green waves" along corridors.
 * A TrafficCoordinator manages a network of intersections, propagating state
 * changes so that a vehicle hitting green at one intersection is likely to
 * hit green at the next.
 *
 * Pattern: Observer
 *
 * Trade-offs:
 * + Decoupled intersections — each only knows about the observer interface
 * + Green-wave corridors emerge from local propagation rules
 * + Priority corridors and cascade timing are configurable per-link
 * - Cyclic observer chains need careful handling to avoid infinite loops
 * - Debugging propagation across many intersections is harder than a central scheduler
 * - Timing drift can accumulate along long corridors
 *
 * When to use:
 * - When coordinating traffic across multiple connected intersections
 * - When "green wave" optimization along arterial roads is desired
 * - When intersections are added/removed dynamically
 *
 * Extensibility:
 * - New coordination rule: Implement IntersectionObserver
 * - New corridor type: Configure CorridorLink with custom offset/priority
 */

enum class IntersectionPhase { NS_GREEN, NS_YELLOW, ALL_RED_NS_TO_EW, EW_GREEN, EW_YELLOW, ALL_RED_EW_TO_NS }

data class PhaseChange(
    val intersectionId: String,
    val oldPhase: IntersectionPhase,
    val newPhase: IntersectionPhase,
    val timestamp: Long
)

interface IntersectionObserver {
    fun onPhaseChange(change: PhaseChange)
    fun onEmergencyOverride(intersectionId: String, vehicle: EmergencyVehicle)
}

data class PhaseTiming(
    val greenDuration: Long = 30_000,
    val yellowDuration: Long = 5_000,
    val allRedDuration: Long = 2_000
)

class CoordinatedIntersection(
    val id: String,
    private val nsLight: TrafficLight,
    private val ewLight: TrafficLight,
    private val sensor: TrafficSensor = DefaultTrafficSensor(),
    private val timing: PhaseTiming = PhaseTiming()
) {
    private var currentPhase = IntersectionPhase.ALL_RED_EW_TO_NS
    private var phaseStartTime = System.currentTimeMillis()
    private var emergencyOverride = false
    private val observers = CopyOnWriteArrayList<IntersectionObserver>()

    fun getCurrentPhase(): IntersectionPhase = currentPhase
    fun getLightStates(): Map<Direction, LightState> = mapOf(Direction.NORTH_SOUTH to nsLight.state, Direction.EAST_WEST to ewLight.state)

    fun addObserver(observer: IntersectionObserver) { observers.add(observer) }
    fun removeObserver(observer: IntersectionObserver) { observers.remove(observer) }

    fun advancePhase() {
        if (emergencyOverride) return

        val nextPhase = when (currentPhase) {
            IntersectionPhase.NS_GREEN -> IntersectionPhase.NS_YELLOW
            IntersectionPhase.NS_YELLOW -> IntersectionPhase.ALL_RED_NS_TO_EW
            IntersectionPhase.ALL_RED_NS_TO_EW -> IntersectionPhase.EW_GREEN
            IntersectionPhase.EW_GREEN -> IntersectionPhase.EW_YELLOW
            IntersectionPhase.EW_YELLOW -> IntersectionPhase.ALL_RED_EW_TO_NS
            IntersectionPhase.ALL_RED_EW_TO_NS -> IntersectionPhase.NS_GREEN
        }
        setPhase(nextPhase)
    }

    fun setPhase(phase: IntersectionPhase) {
        val old = currentPhase
        currentPhase = phase
        phaseStartTime = System.currentTimeMillis()
        applyLights(phase)
        if (old != phase) {
            val change = PhaseChange(id, old, phase, phaseStartTime)
            observers.forEach { it.onPhaseChange(change) }
        }
    }

    fun forceEmergency(vehicle: EmergencyVehicle) {
        emergencyOverride = true
        nsLight.state = LightState.RED
        ewLight.state = LightState.RED
        when (vehicle.direction) {
            Direction.NORTH_SOUTH -> nsLight.state = LightState.GREEN
            Direction.EAST_WEST -> ewLight.state = LightState.GREEN
            Direction.PEDESTRIAN -> {}
        }
        observers.forEach { it.onEmergencyOverride(id, vehicle) }
    }

    fun clearEmergency() {
        emergencyOverride = false
        setPhase(IntersectionPhase.ALL_RED_EW_TO_NS)
    }

    fun getPhaseDuration(): Long = when (currentPhase) {
        IntersectionPhase.NS_GREEN, IntersectionPhase.EW_GREEN -> timing.greenDuration
        IntersectionPhase.NS_YELLOW, IntersectionPhase.EW_YELLOW -> timing.yellowDuration
        IntersectionPhase.ALL_RED_NS_TO_EW, IntersectionPhase.ALL_RED_EW_TO_NS -> timing.allRedDuration
    }

    fun getElapsedInPhase(): Long = System.currentTimeMillis() - phaseStartTime

    private fun applyLights(phase: IntersectionPhase) {
        when (phase) {
            IntersectionPhase.NS_GREEN -> { nsLight.state = LightState.GREEN; ewLight.state = LightState.RED }
            IntersectionPhase.NS_YELLOW -> { nsLight.state = LightState.YELLOW; ewLight.state = LightState.RED }
            IntersectionPhase.ALL_RED_NS_TO_EW -> { nsLight.state = LightState.RED; ewLight.state = LightState.RED }
            IntersectionPhase.EW_GREEN -> { nsLight.state = LightState.RED; ewLight.state = LightState.GREEN }
            IntersectionPhase.EW_YELLOW -> { nsLight.state = LightState.RED; ewLight.state = LightState.YELLOW }
            IntersectionPhase.ALL_RED_EW_TO_NS -> { nsLight.state = LightState.RED; ewLight.state = LightState.RED }
        }
    }
}

data class CorridorLink(
    val from: String,
    val to: String,
    val direction: Direction,
    val offsetMs: Long,
    val priority: Int = 0
)

class GreenWaveObserver(
    private val intersections: Map<String, CoordinatedIntersection>,
    private val links: List<CorridorLink>
) : IntersectionObserver {

    private val linksBySource: Map<String, List<CorridorLink>> = links.groupBy { it.from }
    private val propagationGuard = mutableSetOf<String>()

    override fun onPhaseChange(change: PhaseChange) {
        if (change.intersectionId in propagationGuard) return

        val outgoing = linksBySource[change.intersectionId] ?: return

        for (link in outgoing.sortedByDescending { it.priority }) {
            val target = intersections[link.to] ?: continue
            val desiredPhase = mapDirectionToGreenPhase(link.direction)

            if (change.newPhase == mapDirectionToGreenPhase(link.direction)) {
                propagationGuard.add(link.to)
                try {
                    schedulePhaseWithOffset(target, desiredPhase, link.offsetMs)
                } finally {
                    propagationGuard.remove(link.to)
                }
            }
        }
    }

    override fun onEmergencyOverride(intersectionId: String, vehicle: EmergencyVehicle) {
        val outgoing = linksBySource[intersectionId] ?: return
        for (link in outgoing.filter { it.direction == vehicle.direction }) {
            intersections[link.to]?.forceEmergency(vehicle)
        }
    }

    private fun schedulePhaseWithOffset(target: CoordinatedIntersection, phase: IntersectionPhase, offsetMs: Long) {
        val currentPhase = target.getCurrentPhase()
        if (currentPhase == phase) return

        val stepsToGreen = stepsFromTo(currentPhase, phase)
        if (stepsToGreen <= 2) {
            target.setPhase(phase)
        }
    }

    private fun stepsFromTo(from: IntersectionPhase, to: IntersectionPhase): Int {
        val phases = IntersectionPhase.entries
        val fromIdx = phases.indexOf(from)
        val toIdx = phases.indexOf(to)
        return if (toIdx >= fromIdx) toIdx - fromIdx else phases.size - fromIdx + toIdx
    }

    private fun mapDirectionToGreenPhase(direction: Direction): IntersectionPhase = when (direction) {
        Direction.NORTH_SOUTH -> IntersectionPhase.NS_GREEN
        Direction.EAST_WEST -> IntersectionPhase.EW_GREEN
        Direction.PEDESTRIAN -> IntersectionPhase.ALL_RED_NS_TO_EW
    }
}

class EmergencyCascadeObserver(
    private val intersections: Map<String, CoordinatedIntersection>,
    private val links: List<CorridorLink>
) : IntersectionObserver {

    private val linksBySource: Map<String, List<CorridorLink>> = links.groupBy { it.from }

    override fun onPhaseChange(change: PhaseChange) {}

    override fun onEmergencyOverride(intersectionId: String, vehicle: EmergencyVehicle) {
        val downstream = linksBySource[intersectionId]
            ?.filter { it.direction == vehicle.direction }
            ?: return

        for (link in downstream) {
            intersections[link.to]?.forceEmergency(vehicle)
        }
    }
}

data class CorridorConfig(
    val name: String,
    val intersectionIds: List<String>,
    val direction: Direction,
    val offsetBetweenMs: Long,
    val priority: Int = 0
)

class TrafficCoordinator {
    private val intersections = mutableMapOf<String, CoordinatedIntersection>()
    private val corridors = mutableListOf<CorridorConfig>()
    private val allLinks = mutableListOf<CorridorLink>()
    private var greenWaveObserver: GreenWaveObserver? = null
    private var emergencyCascadeObserver: EmergencyCascadeObserver? = null

    fun addIntersection(intersection: CoordinatedIntersection) {
        intersections[intersection.id] = intersection
    }

    fun removeIntersection(id: String) {
        intersections.remove(id)
        rebuildObservers()
    }

    fun defineCorridor(config: CorridorConfig) {
        corridors.add(config)
        val links = config.intersectionIds.zipWithNext().mapIndexed { idx, (from, to) ->
            CorridorLink(
                from = from,
                to = to,
                direction = config.direction,
                offsetMs = config.offsetBetweenMs * (idx + 1),
                priority = config.priority
            )
        }
        allLinks.addAll(links)
        rebuildObservers()
    }

    fun getIntersection(id: String): CoordinatedIntersection? = intersections[id]
    fun getCorridors(): List<CorridorConfig> = corridors.toList()

    fun getNetworkStatus(): Map<String, Map<Direction, LightState>> {
        return intersections.mapValues { (_, intersection) -> intersection.getLightStates() }
    }

    fun triggerEmergency(intersectionId: String, vehicle: EmergencyVehicle) {
        intersections[intersectionId]?.forceEmergency(vehicle)
    }

    fun clearEmergency(intersectionId: String) {
        intersections[intersectionId]?.clearEmergency()
    }

    fun stepAll() {
        for ((_, intersection) in intersections) {
            if (intersection.getElapsedInPhase() >= intersection.getPhaseDuration()) {
                intersection.advancePhase()
            }
        }
    }

    fun synchronizeCorridor(corridorName: String) {
        val corridor = corridors.find { it.name == corridorName } ?: return
        val phase = mapDirectionToGreenPhase(corridor.direction)

        for ((idx, id) in corridor.intersectionIds.withIndex()) {
            val intersection = intersections[id] ?: continue
            if (idx == 0) {
                intersection.setPhase(phase)
            }
        }
    }

    private fun rebuildObservers() {
        greenWaveObserver?.let { observer ->
            intersections.values.forEach { it.removeObserver(observer) }
        }
        emergencyCascadeObserver?.let { observer ->
            intersections.values.forEach { it.removeObserver(observer) }
        }

        greenWaveObserver = GreenWaveObserver(intersections, allLinks)
        emergencyCascadeObserver = EmergencyCascadeObserver(intersections, allLinks)

        intersections.values.forEach { intersection ->
            intersection.addObserver(greenWaveObserver!!)
            intersection.addObserver(emergencyCascadeObserver!!)
        }
    }

    private fun mapDirectionToGreenPhase(direction: Direction): IntersectionPhase = when (direction) {
        Direction.NORTH_SOUTH -> IntersectionPhase.NS_GREEN
        Direction.EAST_WEST -> IntersectionPhase.EW_GREEN
        Direction.PEDESTRIAN -> IntersectionPhase.ALL_RED_NS_TO_EW
    }
}
