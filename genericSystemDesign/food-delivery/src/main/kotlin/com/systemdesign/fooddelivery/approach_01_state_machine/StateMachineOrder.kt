package com.systemdesign.fooddelivery.approach_01_state_machine

import com.systemdesign.fooddelivery.common.*
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Approach 1: State Machine for Order Lifecycle
 * 
 * Complete order lifecycle management using state machine pattern.
 * Handles restaurant acceptance/rejection and driver assignment/reassignment.
 * 
 * Pattern: State Machine
 * 
 * Trade-offs:
 * + Clear visualization of all possible states and transitions
 * + Invalid transitions are prevented at compile/runtime
 * + Easy to add new states or transitions
 * + State-specific behavior is encapsulated
 * - State explosion possible with many conditional transitions
 * - Need to carefully handle concurrent state changes
 * 
 * When to use:
 * - When object has well-defined lifecycle with clear states
 * - When transitions between states have business rules
 * - When you need to audit state changes
 * 
 * Extensibility:
 * - New state: Add to OrderState enum and update transition rules
 * - New transition conditions: Update canTransition methods
 * - Custom handlers: Register for specific state transitions
 */

/** State transition definition */
data class StateTransition(
    val from: OrderState,
    val to: OrderState,
    val condition: (Order) -> Boolean = { true },
    val action: (Order) -> Order = { it }
)

/** Order state machine managing order lifecycle */
class OrderStateMachine(
    private val assignmentStrategy: DeliveryAssignmentStrategy? = null
) {
    private val orders = ConcurrentHashMap<String, Order>()
    private val observers = mutableListOf<DeliveryObserver>()
    private val eventLog = mutableListOf<DeliveryEvent>()
    
    private val validTransitions = mapOf(
        OrderState.PLACED to setOf(OrderState.ACCEPTED, OrderState.CANCELLED),
        OrderState.ACCEPTED to setOf(OrderState.PREPARING, OrderState.CANCELLED),
        OrderState.PREPARING to setOf(OrderState.READY, OrderState.CANCELLED),
        OrderState.READY to setOf(OrderState.PICKED_UP, OrderState.CANCELLED),
        OrderState.PICKED_UP to setOf(OrderState.IN_TRANSIT),
        OrderState.IN_TRANSIT to setOf(OrderState.DELIVERED),
        OrderState.DELIVERED to emptySet(),
        OrderState.CANCELLED to emptySet()
    )
    
    fun addObserver(observer: DeliveryObserver) {
        observers.add(observer)
    }
    
    fun placeOrder(order: Order): Order {
        require(order.state == OrderState.PLACED) { "New order must be in PLACED state" }
        require(order.items.isNotEmpty()) { "Order must have at least one item" }
        
        orders[order.id] = order
        
        logEvent(DeliveryEvent.OrderPlaced(
            orderId = order.id,
            customerId = order.customer.id,
            restaurantId = order.restaurant.id
        ))
        
        return order
    }
    
    fun acceptOrder(orderId: String, estimatedPrepTime: Int? = null): Order? {
        val order = orders[orderId] ?: return null
        
        if (!canTransition(order, OrderState.ACCEPTED)) {
            return null
        }
        
        val prepTime = estimatedPrepTime ?: order.restaurant.averagePrepTime
        val eta = LocalDateTime.now().plusMinutes(prepTime.toLong() + 30)
        
        val updated = order.copy(
            state = OrderState.ACCEPTED,
            acceptedAt = LocalDateTime.now(),
            estimatedDeliveryTime = eta
        )
        
        orders[orderId] = updated
        notifyStateChange(updated, order.state)
        
        logEvent(DeliveryEvent.OrderAccepted(
            orderId = orderId,
            restaurantId = order.restaurant.id,
            estimatedPrepTime = prepTime
        ))
        
        return updated
    }
    
    fun rejectOrder(orderId: String, reason: CancellationReason): Order? {
        val order = orders[orderId] ?: return null
        
        if (!canTransition(order, OrderState.CANCELLED)) {
            return null
        }
        
        val updated = order.copy(
            state = OrderState.CANCELLED,
            cancelledAt = LocalDateTime.now(),
            cancellationReason = reason
        )
        
        orders[orderId] = updated
        notifyStateChange(updated, order.state)
        
        logEvent(DeliveryEvent.OrderRejected(
            orderId = orderId,
            restaurantId = order.restaurant.id,
            reason = reason
        ))
        
        return updated
    }
    
    fun startPreparing(orderId: String): Order? {
        val order = orders[orderId] ?: return null
        
        if (!canTransition(order, OrderState.PREPARING)) {
            return null
        }
        
        val updated = order.copy(state = OrderState.PREPARING)
        orders[orderId] = updated
        notifyStateChange(updated, order.state)
        
        logEvent(DeliveryEvent.PreparationStarted(orderId = orderId))
        
        return updated
    }
    
    fun markReady(orderId: String): Order? {
        val order = orders[orderId] ?: return null
        
        if (!canTransition(order, OrderState.READY)) {
            return null
        }
        
        val updated = order.copy(
            state = OrderState.READY,
            preparedAt = LocalDateTime.now()
        )
        
        orders[orderId] = updated
        notifyStateChange(updated, order.state)
        
        logEvent(DeliveryEvent.OrderReady(orderId = orderId))
        
        return updated
    }
    
    fun assignAgent(orderId: String, availableAgents: List<DeliveryAgent>): Order? {
        val order = orders[orderId] ?: return null
        
        if (order.state !in listOf(OrderState.ACCEPTED, OrderState.PREPARING, OrderState.READY)) {
            return null
        }
        
        val strategy = assignmentStrategy ?: SimpleNearestAgentStrategy()
        val agent = strategy.findBestAgent(order, availableAgents) ?: return null
        
        val updated = order.withAgent(agent)
        orders[orderId] = updated
        
        notifyAgentAssigned(updated, agent)
        
        val pickupEta = calculatePickupEta(agent, order.restaurant.location)
        logEvent(DeliveryEvent.AgentAssigned(
            orderId = orderId,
            agentId = agent.id,
            estimatedPickupTime = pickupEta
        ))
        
        return updated
    }
    
    fun reassignAgent(
        orderId: String, 
        newAgent: DeliveryAgent, 
        reason: String
    ): Order? {
        val order = orders[orderId] ?: return null
        val previousAgent = order.deliveryAgent ?: return null
        
        if (order.state in listOf(OrderState.DELIVERED, OrderState.CANCELLED)) {
            return null
        }
        
        val updated = order.withAgent(newAgent)
        orders[orderId] = updated
        
        notifyAgentAssigned(updated, newAgent)
        
        logEvent(DeliveryEvent.AgentReassigned(
            orderId = orderId,
            previousAgentId = previousAgent.id,
            newAgentId = newAgent.id,
            reason = reason
        ))
        
        return updated
    }
    
    fun pickupOrder(orderId: String): Order? {
        val order = orders[orderId] ?: return null
        
        if (!canTransition(order, OrderState.PICKED_UP)) {
            return null
        }
        
        if (order.deliveryAgent == null) {
            return null
        }
        
        val updated = order.copy(
            state = OrderState.PICKED_UP,
            pickedUpAt = LocalDateTime.now()
        )
        
        orders[orderId] = updated
        notifyStateChange(updated, order.state)
        
        logEvent(DeliveryEvent.OrderPickedUp(
            orderId = orderId,
            agentId = order.deliveryAgent.id
        ))
        
        return updated
    }
    
    fun startDelivery(orderId: String): Order? {
        val order = orders[orderId] ?: return null
        
        if (!canTransition(order, OrderState.IN_TRANSIT)) {
            return null
        }
        
        val updated = order.copy(state = OrderState.IN_TRANSIT)
        orders[orderId] = updated
        notifyStateChange(updated, order.state)
        
        return updated
    }
    
    fun completeDelivery(orderId: String): Order? {
        val order = orders[orderId] ?: return null
        
        if (!canTransition(order, OrderState.DELIVERED)) {
            return null
        }
        
        val updated = order.copy(
            state = OrderState.DELIVERED,
            deliveredAt = LocalDateTime.now()
        )
        
        orders[orderId] = updated
        notifyStateChange(updated, order.state)
        
        val duration = java.time.Duration.between(
            order.placedAt,
            updated.deliveredAt
        ).toMinutes().toInt()
        
        logEvent(DeliveryEvent.OrderDelivered(
            orderId = orderId,
            agentId = order.deliveryAgent?.id ?: "",
            deliveryDurationMinutes = duration
        ))
        
        return updated
    }
    
    fun cancelOrder(orderId: String, reason: CancellationReason, cancelledBy: String): Order? {
        val order = orders[orderId] ?: return null
        
        if (!order.state.canBeCancelled()) {
            return null
        }
        
        val updated = order.copy(
            state = OrderState.CANCELLED,
            cancelledAt = LocalDateTime.now(),
            cancellationReason = reason
        )
        
        orders[orderId] = updated
        notifyStateChange(updated, order.state)
        
        logEvent(DeliveryEvent.OrderCancelled(
            orderId = orderId,
            reason = reason,
            cancelledBy = cancelledBy
        ))
        
        return updated
    }
    
    fun getOrder(orderId: String): Order? = orders[orderId]
    
    fun getOrdersByState(state: OrderState): List<Order> {
        return orders.values.filter { it.state == state }
    }
    
    fun getActiveOrders(): List<Order> {
        return orders.values.filter { it.isActive() }
    }
    
    fun getOrdersByRestaurant(restaurantId: String): List<Order> {
        return orders.values.filter { it.restaurant.id == restaurantId }
    }
    
    fun getOrdersByCustomer(customerId: String): List<Order> {
        return orders.values.filter { it.customer.id == customerId }
    }
    
    fun getOrdersByAgent(agentId: String): List<Order> {
        return orders.values.filter { it.deliveryAgent?.id == agentId && it.isActive() }
    }
    
    fun getEventLog(orderId: String): List<DeliveryEvent> {
        return eventLog.filter { it.orderId == orderId }
    }
    
    private fun canTransition(order: Order, targetState: OrderState): Boolean {
        val allowedStates = validTransitions[order.state] ?: return false
        return allowedStates.contains(targetState)
    }
    
    private fun notifyStateChange(order: Order, previousState: OrderState) {
        observers.forEach { it.onOrderStateChanged(order, previousState) }
    }
    
    private fun notifyAgentAssigned(order: Order, agent: DeliveryAgent) {
        observers.forEach { it.onAgentAssigned(order, agent) }
    }
    
    private fun logEvent(event: DeliveryEvent) {
        eventLog.add(event)
    }
    
    private fun calculatePickupEta(agent: DeliveryAgent, restaurantLocation: Location): Int {
        val distance = agent.distanceTo(restaurantLocation)
        return (distance / agent.vehicleType.speedKmh * 60).toInt()
    }
}

/** Simple nearest agent strategy for state machine */
class SimpleNearestAgentStrategy : DeliveryAssignmentStrategy {
    override fun findBestAgent(order: Order, availableAgents: List<DeliveryAgent>): DeliveryAgent? {
        return availableAgents
            .filter { it.isAvailable() }
            .minByOrNull { it.distanceTo(order.restaurant.location) }
    }
}

/** Order state machine builder */
class OrderStateMachineBuilder {
    private var assignmentStrategy: DeliveryAssignmentStrategy? = null
    private val observers = mutableListOf<DeliveryObserver>()
    
    fun withAssignmentStrategy(strategy: DeliveryAssignmentStrategy): OrderStateMachineBuilder {
        assignmentStrategy = strategy
        return this
    }
    
    fun withObserver(observer: DeliveryObserver): OrderStateMachineBuilder {
        observers.add(observer)
        return this
    }
    
    fun build(): OrderStateMachine {
        val machine = OrderStateMachine(assignmentStrategy)
        observers.forEach { machine.addObserver(it) }
        return machine
    }
}

/**
 * State handler for individual states
 */
interface OrderStateHandler {
    val state: OrderState
    fun onEnter(order: Order): Order
    fun onExit(order: Order): Order
    fun getAvailableActions(order: Order): List<String>
}

/** Handler for PLACED state */
class PlacedStateHandler : OrderStateHandler {
    override val state = OrderState.PLACED
    
    override fun onEnter(order: Order): Order = order
    
    override fun onExit(order: Order): Order = order
    
    override fun getAvailableActions(order: Order): List<String> {
        return listOf("accept", "reject", "cancel")
    }
}

/** Handler for ACCEPTED state */
class AcceptedStateHandler : OrderStateHandler {
    override val state = OrderState.ACCEPTED
    
    override fun onEnter(order: Order): Order {
        return order.copy(acceptedAt = LocalDateTime.now())
    }
    
    override fun onExit(order: Order): Order = order
    
    override fun getAvailableActions(order: Order): List<String> {
        return listOf("start_preparing", "cancel")
    }
}

/** Handler for PREPARING state */
class PreparingStateHandler : OrderStateHandler {
    override val state = OrderState.PREPARING
    
    override fun onEnter(order: Order): Order = order
    
    override fun onExit(order: Order): Order = order
    
    override fun getAvailableActions(order: Order): List<String> {
        return listOf("mark_ready", "cancel")
    }
}

/** Handler for READY state */
class ReadyStateHandler : OrderStateHandler {
    override val state = OrderState.READY
    
    override fun onEnter(order: Order): Order {
        return order.copy(preparedAt = LocalDateTime.now())
    }
    
    override fun onExit(order: Order): Order = order
    
    override fun getAvailableActions(order: Order): List<String> {
        return if (order.deliveryAgent != null) {
            listOf("pickup")
        } else {
            listOf("assign_agent", "cancel")
        }
    }
}

/** Handler for PICKED_UP state */
class PickedUpStateHandler : OrderStateHandler {
    override val state = OrderState.PICKED_UP
    
    override fun onEnter(order: Order): Order {
        return order.copy(pickedUpAt = LocalDateTime.now())
    }
    
    override fun onExit(order: Order): Order = order
    
    override fun getAvailableActions(order: Order): List<String> {
        return listOf("start_delivery")
    }
}

/** Handler for IN_TRANSIT state */
class InTransitStateHandler : OrderStateHandler {
    override val state = OrderState.IN_TRANSIT
    
    override fun onEnter(order: Order): Order = order
    
    override fun onExit(order: Order): Order = order
    
    override fun getAvailableActions(order: Order): List<String> {
        return listOf("complete_delivery")
    }
}

/** Handler for DELIVERED state */
class DeliveredStateHandler : OrderStateHandler {
    override val state = OrderState.DELIVERED
    
    override fun onEnter(order: Order): Order {
        return order.copy(deliveredAt = LocalDateTime.now())
    }
    
    override fun onExit(order: Order): Order = order
    
    override fun getAvailableActions(order: Order): List<String> {
        return listOf("rate")
    }
}

/** Handler for CANCELLED state */
class CancelledStateHandler : OrderStateHandler {
    override val state = OrderState.CANCELLED
    
    override fun onEnter(order: Order): Order {
        return order.copy(cancelledAt = LocalDateTime.now())
    }
    
    override fun onExit(order: Order): Order = order
    
    override fun getAvailableActions(order: Order): List<String> {
        return emptyList()
    }
}

/**
 * Order state machine service with state handlers
 */
class OrderStateMachineService {
    private val stateHandlers = mapOf(
        OrderState.PLACED to PlacedStateHandler(),
        OrderState.ACCEPTED to AcceptedStateHandler(),
        OrderState.PREPARING to PreparingStateHandler(),
        OrderState.READY to ReadyStateHandler(),
        OrderState.PICKED_UP to PickedUpStateHandler(),
        OrderState.IN_TRANSIT to InTransitStateHandler(),
        OrderState.DELIVERED to DeliveredStateHandler(),
        OrderState.CANCELLED to CancelledStateHandler()
    )
    
    fun getHandler(state: OrderState): OrderStateHandler? = stateHandlers[state]
    
    fun getAvailableActions(order: Order): List<String> {
        return getHandler(order.state)?.getAvailableActions(order) ?: emptyList()
    }
    
    fun transitionOrder(order: Order, targetState: OrderState): Order {
        val exitHandler = getHandler(order.state)
        val enterHandler = getHandler(targetState)
        
        var updated = exitHandler?.onExit(order) ?: order
        updated = updated.withState(targetState)
        updated = enterHandler?.onEnter(updated) ?: updated
        
        return updated
    }
}
