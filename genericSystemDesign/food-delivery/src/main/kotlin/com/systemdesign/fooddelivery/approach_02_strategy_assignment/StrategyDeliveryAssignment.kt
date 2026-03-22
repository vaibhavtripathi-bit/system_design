package com.systemdesign.fooddelivery.approach_02_strategy_assignment

import com.systemdesign.fooddelivery.common.*
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Approach 2: Strategy Pattern for Delivery Agent Assignment
 * 
 * Different assignment algorithms can be swapped based on conditions.
 * Supports nearest agent, load balancing, and rating-based assignment.
 * 
 * Pattern: Strategy Pattern
 * 
 * Trade-offs:
 * + Different strategies for different scenarios (rush hour, high-value orders)
 * + Easy to A/B test assignment algorithms
 * + Algorithms testable in isolation
 * + Runtime strategy switching based on demand
 * - Need to handle agent availability changes during assignment
 * - Complex strategies may have similar performance characteristics
 * 
 * When to use:
 * - When assignment logic varies by time/demand/order type
 * - When experimenting with optimization algorithms
 * - When balancing multiple factors (speed, cost, quality)
 * 
 * Extensibility:
 * - New assignment strategy: Implement DeliveryAssignmentStrategy interface
 * - ML-based assignment: Implement strategy that calls ML service
 * - Composite strategies: Use CompositeAssignmentStrategy to combine multiple
 */

/**
 * Nearest agent strategy
 * 
 * Assigns the closest available agent to minimize pickup time.
 * Best for: Minimizing customer wait time
 * Drawback: May overwork agents in busy areas
 */
class NearestAgentStrategy(
    private val maxDistanceKm: Double = 10.0
) : DeliveryAssignmentStrategy {
    
    override fun findBestAgent(
        order: Order,
        availableAgents: List<DeliveryAgent>
    ): DeliveryAgent? {
        return availableAgents
            .filter { agent ->
                agent.isAvailable() &&
                agent.distanceTo(order.restaurant.location) <= maxDistanceKm &&
                canHandleOrder(agent, order)
            }
            .minByOrNull { it.distanceTo(order.restaurant.location) }
    }
    
    private fun canHandleOrder(agent: DeliveryAgent, order: Order): Boolean {
        val totalDistance = agent.distanceTo(order.restaurant.location) +
                           order.restaurant.location.distanceTo(order.customer.deliveryAddress)
        return totalDistance <= agent.vehicleType.maxDistanceKm
    }
}

/**
 * Load balanced strategy
 * 
 * Distributes orders evenly among available agents.
 * Best for: Fair workload distribution, preventing agent burnout
 * Drawback: May increase delivery time slightly
 */
class LoadBalancedStrategy(
    private val maxDistanceKm: Double = 15.0,
    private val maxOrdersPerAgent: Int = 5
) : DeliveryAssignmentStrategy {
    
    private val agentOrderCounts = ConcurrentHashMap<String, Int>()
    
    override fun findBestAgent(
        order: Order,
        availableAgents: List<DeliveryAgent>
    ): DeliveryAgent? {
        return availableAgents
            .filter { agent ->
                agent.isAvailable() &&
                agent.distanceTo(order.restaurant.location) <= maxDistanceKm &&
                getCurrentOrderCount(agent.id) < maxOrdersPerAgent
            }
            .minByOrNull { getCurrentOrderCount(it.id) }
            ?.also { incrementOrderCount(it.id) }
    }
    
    private fun getCurrentOrderCount(agentId: String): Int {
        return agentOrderCounts[agentId] ?: 0
    }
    
    private fun incrementOrderCount(agentId: String) {
        agentOrderCounts.merge(agentId, 1) { old, new -> old + new }
    }
    
    fun decrementOrderCount(agentId: String) {
        agentOrderCounts.computeIfPresent(agentId) { _, count ->
            if (count <= 1) null else count - 1
        }
    }
    
    fun resetOrderCounts() {
        agentOrderCounts.clear()
    }
    
    fun getOrderCount(agentId: String): Int = agentOrderCounts[agentId] ?: 0
}

/**
 * Rating-based strategy
 * 
 * Prioritizes agents with higher ratings.
 * Best for: Premium orders, ensuring quality delivery
 * Drawback: Lower-rated agents may not get assignments
 */
class RatingBasedStrategy(
    private val minRating: Double = 4.0,
    private val maxDistanceKm: Double = 12.0
) : DeliveryAssignmentStrategy {
    
    override fun findBestAgent(
        order: Order,
        availableAgents: List<DeliveryAgent>
    ): DeliveryAgent? {
        val qualifiedAgents = availableAgents
            .filter { agent ->
                agent.isAvailable() &&
                agent.rating >= minRating &&
                agent.distanceTo(order.restaurant.location) <= maxDistanceKm
            }
        
        if (qualifiedAgents.isEmpty()) {
            return availableAgents
                .filter { it.isAvailable() }
                .maxByOrNull { it.rating }
        }
        
        return qualifiedAgents.maxByOrNull { calculateScore(it, order) }
    }
    
    private fun calculateScore(agent: DeliveryAgent, order: Order): Double {
        val ratingScore = agent.rating * 20
        val distanceScore = 100 - agent.distanceTo(order.restaurant.location) * 5
        val experienceScore = minOf(agent.totalDeliveries.toDouble(), 100.0) / 10
        
        return ratingScore + distanceScore + experienceScore
    }
}

/**
 * Weighted composite strategy
 * 
 * Combines multiple factors with configurable weights.
 * Best for: Balanced optimization across multiple criteria
 */
class WeightedCompositeStrategy(
    private val distanceWeight: Double = 0.4,
    private val ratingWeight: Double = 0.3,
    private val loadWeight: Double = 0.2,
    private val experienceWeight: Double = 0.1,
    private val maxDistanceKm: Double = 15.0,
    private val loadTracker: LoadBalancedStrategy? = null
) : DeliveryAssignmentStrategy {
    
    init {
        require(distanceWeight + ratingWeight + loadWeight + experienceWeight == 1.0) {
            "Weights must sum to 1.0"
        }
    }
    
    override fun findBestAgent(
        order: Order,
        availableAgents: List<DeliveryAgent>
    ): DeliveryAgent? {
        val candidates = availableAgents
            .filter { agent ->
                agent.isAvailable() &&
                agent.distanceTo(order.restaurant.location) <= maxDistanceKm
            }
        
        if (candidates.isEmpty()) return null
        
        val maxDistance = candidates.maxOf { it.distanceTo(order.restaurant.location) }
        val maxRating = candidates.maxOf { it.rating }
        val maxExperience = candidates.maxOf { it.totalDeliveries }.coerceAtLeast(1)
        val maxLoad = loadTracker?.let { tracker ->
            candidates.maxOf { tracker.getOrderCount(it.id) }.coerceAtLeast(1)
        } ?: 1
        
        return candidates.maxByOrNull { agent ->
            val normalizedDistance = 1.0 - (agent.distanceTo(order.restaurant.location) / maxDistance)
            val normalizedRating = agent.rating / maxRating
            val normalizedExperience = agent.totalDeliveries.toDouble() / maxExperience
            val normalizedLoad = loadTracker?.let { tracker ->
                1.0 - (tracker.getOrderCount(agent.id).toDouble() / maxLoad)
            } ?: 1.0
            
            (normalizedDistance * distanceWeight +
             normalizedRating * ratingWeight +
             normalizedLoad * loadWeight +
             normalizedExperience * experienceWeight) * 100
        }
    }
}

/**
 * Zone-based strategy
 * 
 * Assigns agents based on predefined delivery zones.
 * Best for: Geographic optimization, reducing cross-zone deliveries
 */
class ZoneBasedStrategy(
    private val zones: List<DeliveryZone>
) : DeliveryAssignmentStrategy {
    
    data class DeliveryZone(
        val id: String,
        val center: Location,
        val radiusKm: Double,
        val assignedAgentIds: Set<String> = emptySet()
    ) {
        fun contains(location: Location): Boolean {
            return center.isWithinRadius(location, radiusKm)
        }
    }
    
    override fun findBestAgent(
        order: Order,
        availableAgents: List<DeliveryAgent>
    ): DeliveryAgent? {
        val restaurantZone = zones.find { it.contains(order.restaurant.location) }
        val customerZone = zones.find { it.contains(order.customer.deliveryAddress) }
        
        val zoneAgentIds = when {
            restaurantZone != null && customerZone != null && restaurantZone.id == customerZone.id ->
                restaurantZone.assignedAgentIds
            restaurantZone != null ->
                restaurantZone.assignedAgentIds
            else ->
                emptySet()
        }
        
        val zoneAgents = availableAgents.filter { it.id in zoneAgentIds && it.isAvailable() }
        
        if (zoneAgents.isNotEmpty()) {
            return zoneAgents.minByOrNull { it.distanceTo(order.restaurant.location) }
        }
        
        return availableAgents
            .filter { it.isAvailable() }
            .minByOrNull { it.distanceTo(order.restaurant.location) }
    }
}

/**
 * Time-aware strategy
 * 
 * Adjusts assignment based on time of day.
 * Best for: Handling rush hours and quiet periods differently
 */
class TimeAwareStrategy(
    private val rushHourStrategy: DeliveryAssignmentStrategy,
    private val normalStrategy: DeliveryAssignmentStrategy,
    private val rushHourRanges: List<Pair<Int, Int>> = listOf(11 to 14, 18 to 21),
    private val timeProvider: () -> LocalDateTime = { LocalDateTime.now() }
) : DeliveryAssignmentStrategy {
    
    override fun findBestAgent(
        order: Order,
        availableAgents: List<DeliveryAgent>
    ): DeliveryAgent? {
        val currentHour = timeProvider().hour
        val isRushHour = rushHourRanges.any { (start, end) ->
            currentHour in start until end
        }
        
        val strategy = if (isRushHour) rushHourStrategy else normalStrategy
        return strategy.findBestAgent(order, availableAgents)
    }
}

/**
 * Batch assignment strategy
 * 
 * Groups nearby orders and assigns them to the same agent.
 * Best for: Multiple orders from same restaurant area
 */
class BatchAssignmentStrategy(
    private val maxBatchSize: Int = 3,
    private val maxDetourKm: Double = 2.0
) : DeliveryAssignmentStrategy {
    
    private val pendingOrders = mutableListOf<Order>()
    
    override fun findBestAgent(
        order: Order,
        availableAgents: List<DeliveryAgent>
    ): DeliveryAgent? {
        val compatibleBatch = pendingOrders.filter { pending ->
            pending.restaurant.location.isWithinRadius(order.restaurant.location, maxDetourKm)
        }
        
        if (compatibleBatch.size + 1 >= maxBatchSize) {
            val batchOrders = compatibleBatch + order
            val centroid = calculateCentroid(batchOrders.map { it.restaurant.location })
            
            val agent = availableAgents
                .filter { it.isAvailable() }
                .minByOrNull { it.location.distanceTo(centroid) }
            
            if (agent != null) {
                pendingOrders.removeAll(compatibleBatch)
                return agent
            }
        }
        
        pendingOrders.add(order)
        return null
    }
    
    private fun calculateCentroid(locations: List<Location>): Location {
        val avgLat = locations.map { it.lat }.average()
        val avgLng = locations.map { it.lng }.average()
        return Location(avgLat, avgLng)
    }
    
    fun getPendingOrderCount(): Int = pendingOrders.size
    
    fun flushPendingOrders(): List<Order> {
        val orders = pendingOrders.toList()
        pendingOrders.clear()
        return orders
    }
}

/**
 * Priority-based strategy
 * 
 * Prioritizes premium/VIP customers.
 * Best for: Tiered service levels
 */
class PriorityBasedStrategy(
    private val premiumStrategy: DeliveryAssignmentStrategy,
    private val normalStrategy: DeliveryAssignmentStrategy,
    private val premiumCustomerChecker: (String) -> Boolean
) : DeliveryAssignmentStrategy {
    
    override fun findBestAgent(
        order: Order,
        availableAgents: List<DeliveryAgent>
    ): DeliveryAgent? {
        val strategy = if (premiumCustomerChecker(order.customer.id)) {
            premiumStrategy
        } else {
            normalStrategy
        }
        
        return strategy.findBestAgent(order, availableAgents)
    }
}

/**
 * Assignment result with metadata
 */
data class AssignmentResult(
    val agent: DeliveryAgent?,
    val estimatedPickupMinutes: Int,
    val estimatedDeliveryMinutes: Int,
    val score: Double,
    val strategy: String
)

/**
 * Delivery assignment service managing agent pool and assignments
 */
class DeliveryAssignmentService(
    private var strategy: DeliveryAssignmentStrategy = NearestAgentStrategy()
) {
    private val availableAgents = ConcurrentHashMap<String, DeliveryAgent>()
    private val assignmentHistory = mutableListOf<AssignmentRecord>()
    
    data class AssignmentRecord(
        val orderId: String,
        val agentId: String,
        val assignedAt: LocalDateTime,
        val strategy: String
    )
    
    fun setStrategy(newStrategy: DeliveryAssignmentStrategy) {
        strategy = newStrategy
    }
    
    fun registerAgent(agent: DeliveryAgent) {
        availableAgents[agent.id] = agent
    }
    
    fun unregisterAgent(agentId: String) {
        availableAgents.remove(agentId)
    }
    
    fun updateAgentLocation(agentId: String, location: Location) {
        availableAgents[agentId]?.let { agent ->
            availableAgents[agentId] = agent.withLocation(location)
        }
    }
    
    fun updateAgentStatus(agentId: String, status: AgentStatus) {
        availableAgents[agentId]?.let { agent ->
            availableAgents[agentId] = agent.withStatus(status)
        }
    }
    
    fun findAgent(order: Order): AssignmentResult {
        val agents = availableAgents.values.toList()
        val agent = strategy.findBestAgent(order, agents)
        
        val pickupEta = agent?.let { calculatePickupEta(it, order.restaurant.location) } ?: 0
        val deliveryEta = agent?.let { 
            calculateDeliveryEta(order.restaurant.location, order.customer.deliveryAddress, it)
        } ?: 0
        
        if (agent != null) {
            assignmentHistory.add(AssignmentRecord(
                orderId = order.id,
                agentId = agent.id,
                assignedAt = LocalDateTime.now(),
                strategy = strategy::class.simpleName ?: "Unknown"
            ))
            
            availableAgents[agent.id] = agent
                .withStatus(AgentStatus.ASSIGNED)
                .withCurrentOrder(order.id)
        }
        
        return AssignmentResult(
            agent = agent,
            estimatedPickupMinutes = pickupEta,
            estimatedDeliveryMinutes = deliveryEta,
            score = agent?.rating ?: 0.0,
            strategy = strategy::class.simpleName ?: "Unknown"
        )
    }
    
    fun releaseAgent(agentId: String) {
        availableAgents[agentId]?.let { agent ->
            availableAgents[agentId] = agent
                .withStatus(AgentStatus.AVAILABLE)
                .withCurrentOrder(null)
        }
    }
    
    private fun calculatePickupEta(agent: DeliveryAgent, restaurantLocation: Location): Int {
        val distance = agent.distanceTo(restaurantLocation)
        return (distance / agent.vehicleType.speedKmh * 60).toInt()
    }
    
    private fun calculateDeliveryEta(
        restaurantLocation: Location,
        customerLocation: Location,
        agent: DeliveryAgent
    ): Int {
        val distance = restaurantLocation.distanceTo(customerLocation)
        return (distance / agent.vehicleType.speedKmh * 60).toInt()
    }
    
    fun getAvailableAgentCount(): Int = availableAgents.values.count { it.isAvailable() }
    
    fun getAgentsByStatus(status: AgentStatus): List<DeliveryAgent> {
        return availableAgents.values.filter { it.status == status }
    }
    
    fun getAssignmentHistory(orderId: String): List<AssignmentRecord> {
        return assignmentHistory.filter { it.orderId == orderId }
    }
}

/**
 * Strategy factory for creating assignment strategies
 */
object AssignmentStrategyFactory {
    
    fun createNearest(maxDistanceKm: Double = 10.0): DeliveryAssignmentStrategy {
        return NearestAgentStrategy(maxDistanceKm)
    }
    
    fun createLoadBalanced(
        maxDistanceKm: Double = 15.0,
        maxOrdersPerAgent: Int = 5
    ): LoadBalancedStrategy {
        return LoadBalancedStrategy(maxDistanceKm, maxOrdersPerAgent)
    }
    
    fun createRatingBased(
        minRating: Double = 4.0,
        maxDistanceKm: Double = 12.0
    ): DeliveryAssignmentStrategy {
        return RatingBasedStrategy(minRating, maxDistanceKm)
    }
    
    fun createWeightedComposite(
        distanceWeight: Double = 0.4,
        ratingWeight: Double = 0.3,
        loadWeight: Double = 0.2,
        experienceWeight: Double = 0.1
    ): DeliveryAssignmentStrategy {
        return WeightedCompositeStrategy(
            distanceWeight, ratingWeight, loadWeight, experienceWeight
        )
    }
    
    fun createTimeAware(
        rushHourStrategy: DeliveryAssignmentStrategy = RatingBasedStrategy(),
        normalStrategy: DeliveryAssignmentStrategy = NearestAgentStrategy()
    ): DeliveryAssignmentStrategy {
        return TimeAwareStrategy(rushHourStrategy, normalStrategy)
    }
}
