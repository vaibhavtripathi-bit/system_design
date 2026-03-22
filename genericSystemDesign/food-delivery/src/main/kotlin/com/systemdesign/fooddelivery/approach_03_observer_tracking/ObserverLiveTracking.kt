package com.systemdesign.fooddelivery.approach_03_observer_tracking

import com.systemdesign.fooddelivery.common.*
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Approach 3: Observer Pattern for Live Order Tracking
 * 
 * Real-time tracking with location updates, ETA calculations,
 * and customer notification triggers.
 * 
 * Pattern: Observer Pattern
 * 
 * Trade-offs:
 * + Decoupled publishers and subscribers
 * + Easy to add new notification types
 * + Multiple observers can react to same event
 * + Event-driven architecture for real-time updates
 * - Need to manage observer lifecycle
 * - Potential memory leaks if observers not unregistered
 * 
 * When to use:
 * - When multiple components need to react to state changes
 * - When building real-time tracking features
 * - When notifications should be triggered by events
 * 
 * Extensibility:
 * - New observer: Implement DeliveryObserver interface
 * - New events: Add to DeliveryEvent sealed class
 * - Custom notifications: Create specific observer implementations
 */

/**
 * Live tracking service for orders
 * 
 * Manages real-time location updates, ETA calculations,
 * and notifies observers of changes.
 */
class LiveTrackingService {
    
    private val observers = mutableListOf<DeliveryObserver>()
    private val orderTracking = ConcurrentHashMap<String, OrderTrackingInfo>()
    private val agentLocations = ConcurrentHashMap<String, AgentLocationInfo>()
    
    fun addObserver(observer: DeliveryObserver) {
        observers.add(observer)
    }
    
    fun removeObserver(observer: DeliveryObserver) {
        observers.remove(observer)
    }
    
    fun startTracking(order: Order, agent: DeliveryAgent) {
        val tracking = OrderTrackingInfo(
            orderId = order.id,
            agentId = agent.id,
            restaurantLocation = order.restaurant.location,
            customerLocation = order.customer.deliveryAddress,
            currentPhase = TrackingPhase.AGENT_TO_RESTAURANT,
            startedAt = LocalDateTime.now()
        )
        
        orderTracking[order.id] = tracking
        
        agentLocations[agent.id] = AgentLocationInfo(
            agentId = agent.id,
            location = agent.location,
            lastUpdatedAt = LocalDateTime.now()
        )
        
        notifyAgentAssigned(order, agent)
    }
    
    fun updateAgentLocation(agentId: String, newLocation: Location) {
        val previousInfo = agentLocations[agentId]
        
        agentLocations[agentId] = AgentLocationInfo(
            agentId = agentId,
            location = newLocation,
            lastUpdatedAt = LocalDateTime.now()
        )
        
        val affectedOrders = orderTracking.values.filter { it.agentId == agentId }
        
        for (tracking in affectedOrders) {
            val eta = calculateETA(tracking, newLocation)
            
            val updatedTracking = tracking.copy(
                lastKnownLocation = newLocation,
                currentETA = eta,
                lastUpdatedAt = LocalDateTime.now()
            )
            
            orderTracking[tracking.orderId] = updatedTracking
            
            notifyLocationUpdate(tracking.orderId, newLocation)
            notifyETAUpdate(tracking.orderId, eta)
            
            checkAndNotifyMilestones(updatedTracking, previousInfo?.location)
        }
    }
    
    fun agentArrivedAtRestaurant(orderId: String) {
        orderTracking[orderId]?.let { tracking ->
            val updated = tracking.copy(
                currentPhase = TrackingPhase.AT_RESTAURANT,
                arrivedAtRestaurantAt = LocalDateTime.now()
            )
            orderTracking[orderId] = updated
            
            notifyMilestone(orderId, "Agent arrived at restaurant")
        }
    }
    
    fun orderPickedUp(orderId: String) {
        orderTracking[orderId]?.let { tracking ->
            val updated = tracking.copy(
                currentPhase = TrackingPhase.AGENT_TO_CUSTOMER,
                pickedUpAt = LocalDateTime.now()
            )
            orderTracking[orderId] = updated
            
            notifyMilestone(orderId, "Order picked up, on the way")
        }
    }
    
    fun agentArrivedAtCustomer(orderId: String) {
        orderTracking[orderId]?.let { tracking ->
            val updated = tracking.copy(
                currentPhase = TrackingPhase.AT_CUSTOMER,
                arrivedAtCustomerAt = LocalDateTime.now()
            )
            orderTracking[orderId] = updated
            
            notifyMilestone(orderId, "Agent arrived at delivery location")
        }
    }
    
    fun orderDelivered(orderId: String) {
        orderTracking[orderId]?.let { tracking ->
            val updated = tracking.copy(
                currentPhase = TrackingPhase.DELIVERED,
                deliveredAt = LocalDateTime.now()
            )
            orderTracking[orderId] = updated
            
            notifyMilestone(orderId, "Order delivered")
            
            orderTracking.remove(orderId)
        }
    }
    
    fun stopTracking(orderId: String) {
        orderTracking.remove(orderId)
    }
    
    fun getTrackingInfo(orderId: String): OrderTrackingInfo? = orderTracking[orderId]
    
    fun getAgentLocation(agentId: String): Location? = agentLocations[agentId]?.location
    
    fun getCurrentETA(orderId: String): ETAEstimate? {
        val tracking = orderTracking[orderId] ?: return null
        val agentLocation = agentLocations[tracking.agentId]?.location ?: return null
        
        return calculateETA(tracking, agentLocation)
    }
    
    private fun calculateETA(tracking: OrderTrackingInfo, agentLocation: Location): ETAEstimate {
        val now = LocalDateTime.now()
        
        val (pickupMinutes, deliveryMinutes) = when (tracking.currentPhase) {
            TrackingPhase.AGENT_TO_RESTAURANT -> {
                val toRestaurant = calculateTravelTime(agentLocation, tracking.restaurantLocation)
                val toCustomer = calculateTravelTime(tracking.restaurantLocation, tracking.customerLocation)
                toRestaurant to toCustomer
            }
            TrackingPhase.AT_RESTAURANT -> {
                0 to calculateTravelTime(tracking.restaurantLocation, tracking.customerLocation)
            }
            TrackingPhase.AGENT_TO_CUSTOMER -> {
                0 to calculateTravelTime(agentLocation, tracking.customerLocation)
            }
            TrackingPhase.AT_CUSTOMER, TrackingPhase.DELIVERED -> {
                0 to 0
            }
        }
        
        val prepMinutes = tracking.estimatedPrepMinutes ?: 0
        val remainingPrepMinutes = if (tracking.currentPhase == TrackingPhase.AGENT_TO_RESTAURANT) {
            maxOf(0, prepMinutes - calculateElapsedMinutes(tracking.startedAt, now))
        } else {
            0
        }
        
        val totalMinutes = remainingPrepMinutes + pickupMinutes + deliveryMinutes
        
        return ETAEstimate(
            estimatedTime = now.plusMinutes(totalMinutes.toLong()),
            preparationMinutes = remainingPrepMinutes,
            pickupMinutes = pickupMinutes,
            deliveryMinutes = deliveryMinutes,
            confidence = calculateConfidence(tracking.currentPhase)
        )
    }
    
    private fun calculateTravelTime(from: Location, to: Location): Int {
        val distance = from.distanceTo(to)
        val speedKmh = 25.0
        return (distance / speedKmh * 60).toInt().coerceAtLeast(1)
    }
    
    private fun calculateElapsedMinutes(start: LocalDateTime, end: LocalDateTime): Int {
        return java.time.Duration.between(start, end).toMinutes().toInt()
    }
    
    private fun calculateConfidence(phase: TrackingPhase): Double {
        return when (phase) {
            TrackingPhase.AGENT_TO_RESTAURANT -> 0.6
            TrackingPhase.AT_RESTAURANT -> 0.7
            TrackingPhase.AGENT_TO_CUSTOMER -> 0.85
            TrackingPhase.AT_CUSTOMER -> 0.95
            TrackingPhase.DELIVERED -> 1.0
        }
    }
    
    private fun checkAndNotifyMilestones(tracking: OrderTrackingInfo, previousLocation: Location?) {
        val currentLocation = tracking.lastKnownLocation ?: return
        
        when (tracking.currentPhase) {
            TrackingPhase.AGENT_TO_RESTAURANT -> {
                if (currentLocation.isWithinRadius(tracking.restaurantLocation, 0.1)) {
                    agentArrivedAtRestaurant(tracking.orderId)
                }
            }
            TrackingPhase.AGENT_TO_CUSTOMER -> {
                if (currentLocation.isWithinRadius(tracking.customerLocation, 0.1)) {
                    agentArrivedAtCustomer(tracking.orderId)
                }
                
                if (currentLocation.isWithinRadius(tracking.customerLocation, 0.5) &&
                    previousLocation?.isWithinRadius(tracking.customerLocation, 0.5) != true) {
                    notifyMilestone(tracking.orderId, "Agent is nearby")
                }
            }
            else -> {}
        }
    }
    
    private fun notifyLocationUpdate(orderId: String, location: Location) {
        observers.forEach { it.onAgentLocationUpdated(orderId, location) }
    }
    
    private fun notifyETAUpdate(orderId: String, eta: ETAEstimate) {
        observers.forEach { it.onETAUpdated(orderId, eta) }
    }
    
    private fun notifyAgentAssigned(order: Order, agent: DeliveryAgent) {
        observers.forEach { it.onAgentAssigned(order, agent) }
    }
    
    private fun notifyMilestone(orderId: String, message: String) {
        // Milestone notifications through a custom observer interface
    }
}

/** Tracking phases */
enum class TrackingPhase {
    AGENT_TO_RESTAURANT,
    AT_RESTAURANT,
    AGENT_TO_CUSTOMER,
    AT_CUSTOMER,
    DELIVERED
}

/** Order tracking information */
data class OrderTrackingInfo(
    val orderId: String,
    val agentId: String,
    val restaurantLocation: Location,
    val customerLocation: Location,
    val currentPhase: TrackingPhase,
    val startedAt: LocalDateTime,
    val lastKnownLocation: Location? = null,
    val currentETA: ETAEstimate? = null,
    val lastUpdatedAt: LocalDateTime = LocalDateTime.now(),
    val arrivedAtRestaurantAt: LocalDateTime? = null,
    val pickedUpAt: LocalDateTime? = null,
    val arrivedAtCustomerAt: LocalDateTime? = null,
    val deliveredAt: LocalDateTime? = null,
    val estimatedPrepMinutes: Int? = null
)

/** Agent location information */
data class AgentLocationInfo(
    val agentId: String,
    val location: Location,
    val lastUpdatedAt: LocalDateTime
)

/**
 * Customer notification observer
 * 
 * Sends notifications to customers about order status
 */
class CustomerNotificationObserver(
    private val notificationService: CustomerNotificationService
) : DeliveryObserver {
    
    override fun onOrderStateChanged(order: Order, previousState: OrderState) {
        val message = when (order.state) {
            OrderState.ACCEPTED -> "Your order has been accepted by ${order.restaurant.name}"
            OrderState.PREPARING -> "Your order is being prepared"
            OrderState.READY -> "Your order is ready for pickup"
            OrderState.PICKED_UP -> "Your order is on its way"
            OrderState.DELIVERED -> "Your order has been delivered. Enjoy!"
            OrderState.CANCELLED -> "Your order has been cancelled"
            else -> return
        }
        
        notificationService.sendNotification(order.customer.id, "Order Update", message)
    }
    
    override fun onAgentAssigned(order: Order, agent: DeliveryAgent) {
        val message = "${agent.name} will deliver your order"
        notificationService.sendNotification(order.customer.id, "Driver Assigned", message)
    }
    
    override fun onAgentLocationUpdated(orderId: String, location: Location) {
        // Location updates typically shown on map, not sent as notification
    }
    
    override fun onETAUpdated(orderId: String, eta: ETAEstimate) {
        // ETA updates shown in app, significant changes could trigger notification
    }
}

/** Customer notification service interface */
interface CustomerNotificationService {
    fun sendNotification(customerId: String, title: String, message: String)
    fun sendPushNotification(customerId: String, title: String, message: String, data: Map<String, Any>)
}

/** Mock notification service */
class MockCustomerNotificationService : CustomerNotificationService {
    
    val notifications = mutableListOf<SentNotification>()
    
    data class SentNotification(
        val customerId: String,
        val title: String,
        val message: String,
        val sentAt: LocalDateTime = LocalDateTime.now()
    )
    
    override fun sendNotification(customerId: String, title: String, message: String) {
        notifications.add(SentNotification(customerId, title, message))
    }
    
    override fun sendPushNotification(
        customerId: String,
        title: String,
        message: String,
        data: Map<String, Any>
    ) {
        notifications.add(SentNotification(customerId, title, message))
    }
}

/**
 * Restaurant notification observer
 * 
 * Notifies restaurants about new orders and agent arrivals
 */
class RestaurantNotificationObserver(
    private val notificationService: RestaurantNotificationService
) : DeliveryObserver {
    
    override fun onOrderStateChanged(order: Order, previousState: OrderState) {
        if (order.state == OrderState.PLACED) {
            notificationService.notifyNewOrder(order.restaurant.id, order.id)
        }
    }
    
    override fun onAgentAssigned(order: Order, agent: DeliveryAgent) {
        notificationService.notifyAgentAssigned(
            order.restaurant.id,
            order.id,
            agent.name,
            calculateAgentETA(agent, order.restaurant.location)
        )
    }
    
    override fun onAgentLocationUpdated(orderId: String, location: Location) {
        // Could notify restaurant when agent is nearby
    }
    
    override fun onETAUpdated(orderId: String, eta: ETAEstimate) {
        // Restaurant might want to know when to start preparing
    }
    
    private fun calculateAgentETA(agent: DeliveryAgent, restaurantLocation: Location): Int {
        val distance = agent.distanceTo(restaurantLocation)
        return (distance / agent.vehicleType.speedKmh * 60).toInt()
    }
}

/** Restaurant notification service interface */
interface RestaurantNotificationService {
    fun notifyNewOrder(restaurantId: String, orderId: String)
    fun notifyAgentAssigned(restaurantId: String, orderId: String, agentName: String, etaMinutes: Int)
    fun notifyAgentArrived(restaurantId: String, orderId: String)
}

/**
 * Analytics observer
 * 
 * Collects metrics and analytics data from delivery events
 */
class AnalyticsObserver : DeliveryObserver {
    
    private val metrics = DeliveryMetricsCollector()
    
    override fun onOrderStateChanged(order: Order, previousState: OrderState) {
        metrics.recordStateTransition(order.id, previousState, order.state)
        
        if (order.state == OrderState.DELIVERED) {
            val duration = java.time.Duration.between(
                order.placedAt,
                order.deliveredAt ?: LocalDateTime.now()
            ).toMinutes().toInt()
            
            metrics.recordDeliveryCompletion(order.id, duration)
        }
    }
    
    override fun onAgentAssigned(order: Order, agent: DeliveryAgent) {
        val waitTime = java.time.Duration.between(
            order.placedAt,
            LocalDateTime.now()
        ).toMinutes().toInt()
        
        metrics.recordAssignmentTime(order.id, waitTime)
    }
    
    override fun onAgentLocationUpdated(orderId: String, location: Location) {
        metrics.recordLocationUpdate(orderId)
    }
    
    override fun onETAUpdated(orderId: String, eta: ETAEstimate) {
        metrics.recordETAUpdate(orderId, eta.totalMinutes)
    }
    
    fun getMetrics(): DeliveryMetrics = metrics.computeMetrics()
}

/** Metrics collector */
class DeliveryMetricsCollector {
    
    private val deliveryTimes = mutableListOf<Int>()
    private val assignmentTimes = mutableListOf<Int>()
    private val locationUpdates = mutableMapOf<String, Int>()
    private val etaAccuracy = mutableListOf<Pair<Int, Int>>()
    
    fun recordStateTransition(orderId: String, from: OrderState, to: OrderState) {
        // Record state transitions
    }
    
    fun recordDeliveryCompletion(orderId: String, durationMinutes: Int) {
        deliveryTimes.add(durationMinutes)
    }
    
    fun recordAssignmentTime(orderId: String, waitTimeMinutes: Int) {
        assignmentTimes.add(waitTimeMinutes)
    }
    
    fun recordLocationUpdate(orderId: String) {
        locationUpdates.merge(orderId, 1) { old, new -> old + new }
    }
    
    fun recordETAUpdate(orderId: String, etaMinutes: Int) {
        // Track ETA changes for accuracy calculation
    }
    
    fun computeMetrics(): DeliveryMetrics {
        val avgDeliveryTime = if (deliveryTimes.isNotEmpty()) {
            deliveryTimes.average().toInt()
        } else 0
        
        val onTimeRate = if (deliveryTimes.isNotEmpty()) {
            deliveryTimes.count { it <= 45 }.toDouble() / deliveryTimes.size
        } else 0.0
        
        return DeliveryMetrics(
            averageDeliveryTime = avgDeliveryTime,
            onTimeDeliveryRate = onTimeRate,
            averageRating = 4.5,
            totalDeliveries = deliveryTimes.size
        )
    }
}

/**
 * Real-time updates observer for WebSocket/SSE
 * 
 * Pushes updates to connected clients
 */
class RealTimeUpdatesObserver(
    private val updatePublisher: RealTimeUpdatePublisher
) : DeliveryObserver {
    
    override fun onOrderStateChanged(order: Order, previousState: OrderState) {
        updatePublisher.publishOrderUpdate(
            orderId = order.id,
            customerId = order.customer.id,
            update = OrderUpdate(
                type = "state_change",
                state = order.state.name,
                timestamp = LocalDateTime.now()
            )
        )
    }
    
    override fun onAgentAssigned(order: Order, agent: DeliveryAgent) {
        updatePublisher.publishOrderUpdate(
            orderId = order.id,
            customerId = order.customer.id,
            update = OrderUpdate(
                type = "agent_assigned",
                agentName = agent.name,
                agentPhone = agent.phone,
                timestamp = LocalDateTime.now()
            )
        )
    }
    
    override fun onAgentLocationUpdated(orderId: String, location: Location) {
        updatePublisher.publishLocationUpdate(orderId, location)
    }
    
    override fun onETAUpdated(orderId: String, eta: ETAEstimate) {
        updatePublisher.publishETAUpdate(orderId, eta)
    }
}

/** Order update data */
data class OrderUpdate(
    val type: String,
    val state: String? = null,
    val agentName: String? = null,
    val agentPhone: String? = null,
    val timestamp: LocalDateTime
)

/** Real-time update publisher interface */
interface RealTimeUpdatePublisher {
    fun publishOrderUpdate(orderId: String, customerId: String, update: OrderUpdate)
    fun publishLocationUpdate(orderId: String, location: Location)
    fun publishETAUpdate(orderId: String, eta: ETAEstimate)
}

/** Mock real-time publisher */
class MockRealTimeUpdatePublisher : RealTimeUpdatePublisher {
    
    val orderUpdates = mutableListOf<Triple<String, String, OrderUpdate>>()
    val locationUpdates = mutableListOf<Pair<String, Location>>()
    val etaUpdates = mutableListOf<Pair<String, ETAEstimate>>()
    
    override fun publishOrderUpdate(orderId: String, customerId: String, update: OrderUpdate) {
        orderUpdates.add(Triple(orderId, customerId, update))
    }
    
    override fun publishLocationUpdate(orderId: String, location: Location) {
        locationUpdates.add(orderId to location)
    }
    
    override fun publishETAUpdate(orderId: String, eta: ETAEstimate) {
        etaUpdates.add(orderId to eta)
    }
}

/**
 * Tracking service with all observers registered
 */
class DeliveryTrackingService(
    private val trackingService: LiveTrackingService = LiveTrackingService(),
    private val notificationService: CustomerNotificationService? = null,
    private val updatePublisher: RealTimeUpdatePublisher? = null
) {
    init {
        notificationService?.let {
            trackingService.addObserver(CustomerNotificationObserver(it))
        }
        
        updatePublisher?.let {
            trackingService.addObserver(RealTimeUpdatesObserver(it))
        }
        
        trackingService.addObserver(AnalyticsObserver())
    }
    
    fun startTracking(order: Order, agent: DeliveryAgent) {
        trackingService.startTracking(order, agent)
    }
    
    fun updateLocation(agentId: String, location: Location) {
        trackingService.updateAgentLocation(agentId, location)
    }
    
    fun orderPickedUp(orderId: String) {
        trackingService.orderPickedUp(orderId)
    }
    
    fun orderDelivered(orderId: String) {
        trackingService.orderDelivered(orderId)
    }
    
    fun getTrackingInfo(orderId: String): OrderTrackingInfo? {
        return trackingService.getTrackingInfo(orderId)
    }
    
    fun getCurrentETA(orderId: String): ETAEstimate? {
        return trackingService.getCurrentETA(orderId)
    }
}
