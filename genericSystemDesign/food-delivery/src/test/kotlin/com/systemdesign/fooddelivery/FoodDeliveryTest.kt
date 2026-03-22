package com.systemdesign.fooddelivery

import com.systemdesign.fooddelivery.common.*
import com.systemdesign.fooddelivery.approach_01_state_machine.*
import com.systemdesign.fooddelivery.approach_02_strategy_assignment.*
import com.systemdesign.fooddelivery.approach_03_observer_tracking.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import java.time.LocalDateTime

class FoodDeliveryTest {
    
    private val restaurantLocation = Location(37.7749, -122.4194, "123 Restaurant St")
    private val customerLocation = Location(37.7849, -122.4094, "456 Customer Ave")
    private val agentLocation = Location(37.7799, -122.4144, "Agent Location")
    
    private fun createTestRestaurant(
        id: String = "restaurant-1",
        name: String = "Test Restaurant"
    ): Restaurant {
        return Restaurant(
            id = id,
            name = name,
            location = restaurantLocation,
            cuisineTypes = setOf(CuisineType.ITALIAN),
            rating = 4.5,
            isOpen = true,
            averagePrepTime = 20
        )
    }
    
    private fun createTestCustomer(
        id: String = "customer-1"
    ): Customer {
        return Customer(
            id = id,
            name = "Test Customer",
            phone = "+1234567890",
            email = "customer@test.com",
            deliveryAddress = customerLocation
        )
    }
    
    private fun createTestMenuItem(
        restaurantId: String = "restaurant-1",
        price: Double = 15.0
    ): MenuItem {
        return MenuItem(
            restaurantId = restaurantId,
            name = "Test Item",
            description = "Delicious test item",
            price = price,
            category = "Main",
            isAvailable = true,
            preparationTime = 15
        )
    }
    
    private fun createTestAgent(
        id: String = "agent-1",
        location: Location = agentLocation,
        status: AgentStatus = AgentStatus.AVAILABLE,
        rating: Double = 4.8
    ): DeliveryAgent {
        return DeliveryAgent(
            id = id,
            name = "Test Agent",
            phone = "+0987654321",
            location = location,
            status = status,
            rating = rating,
            totalDeliveries = 100,
            vehicleType = VehicleType.MOTORCYCLE
        )
    }
    
    private fun createTestOrder(
        id: String = "order-1",
        state: OrderState = OrderState.PLACED,
        agent: DeliveryAgent? = null
    ): Order {
        val restaurant = createTestRestaurant()
        val customer = createTestCustomer()
        val menuItem = createTestMenuItem(restaurant.id)
        val orderItem = OrderItem(menuItem = menuItem, quantity = 2)
        
        return Order(
            id = id,
            customer = customer,
            restaurant = restaurant,
            items = listOf(orderItem),
            state = state,
            deliveryAgent = agent
        )
    }
    
    @Nested
    inner class StateMachineTest {
        
        private lateinit var stateMachine: OrderStateMachine
        
        @BeforeEach
        fun setup() {
            stateMachine = OrderStateMachine()
        }
        
        @Test
        fun `order starts in PLACED state`() {
            val order = createTestOrder()
            val placed = stateMachine.placeOrder(order)
            
            assertEquals(OrderState.PLACED, placed.state)
            assertNotNull(stateMachine.getOrder(order.id))
        }
        
        @Test
        fun `order transitions from PLACED to ACCEPTED`() {
            val order = createTestOrder()
            stateMachine.placeOrder(order)
            
            val accepted = stateMachine.acceptOrder(order.id)
            
            assertNotNull(accepted)
            assertEquals(OrderState.ACCEPTED, accepted!!.state)
            assertNotNull(accepted.acceptedAt)
        }
        
        @Test
        fun `order can be rejected from PLACED state`() {
            val order = createTestOrder()
            stateMachine.placeOrder(order)
            
            val rejected = stateMachine.rejectOrder(order.id, CancellationReason.RESTAURANT_CLOSED)
            
            assertNotNull(rejected)
            assertEquals(OrderState.CANCELLED, rejected!!.state)
            assertEquals(CancellationReason.RESTAURANT_CLOSED, rejected.cancellationReason)
        }
        
        @Test
        fun `order transitions through preparation phases`() {
            val order = createTestOrder()
            stateMachine.placeOrder(order)
            stateMachine.acceptOrder(order.id)
            
            val preparing = stateMachine.startPreparing(order.id)
            assertEquals(OrderState.PREPARING, preparing!!.state)
            
            val ready = stateMachine.markReady(order.id)
            assertEquals(OrderState.READY, ready!!.state)
            assertNotNull(ready.preparedAt)
        }
        
        @Test
        fun `agent can be assigned to order`() {
            val order = createTestOrder()
            stateMachine.placeOrder(order)
            stateMachine.acceptOrder(order.id)
            
            val agent = createTestAgent()
            val updated = stateMachine.assignAgent(order.id, listOf(agent))
            
            assertNotNull(updated)
            assertNotNull(updated!!.deliveryAgent)
            assertEquals(agent.id, updated.deliveryAgent!!.id)
        }
        
        @Test
        fun `agent can be reassigned`() {
            val order = createTestOrder()
            stateMachine.placeOrder(order)
            stateMachine.acceptOrder(order.id)
            
            val agent1 = createTestAgent(id = "agent-1")
            val agent2 = createTestAgent(id = "agent-2")
            
            stateMachine.assignAgent(order.id, listOf(agent1))
            val reassigned = stateMachine.reassignAgent(order.id, agent2, "Agent unavailable")
            
            assertNotNull(reassigned)
            assertEquals("agent-2", reassigned!!.deliveryAgent!!.id)
        }
        
        @Test
        fun `order transitions through delivery phases`() {
            val order = createTestOrder()
            val agent = createTestAgent()
            
            stateMachine.placeOrder(order)
            stateMachine.acceptOrder(order.id)
            stateMachine.startPreparing(order.id)
            stateMachine.markReady(order.id)
            stateMachine.assignAgent(order.id, listOf(agent))
            
            val pickedUp = stateMachine.pickupOrder(order.id)
            assertEquals(OrderState.PICKED_UP, pickedUp!!.state)
            assertNotNull(pickedUp.pickedUpAt)
            
            val inTransit = stateMachine.startDelivery(order.id)
            assertEquals(OrderState.IN_TRANSIT, inTransit!!.state)
            
            val delivered = stateMachine.completeDelivery(order.id)
            assertEquals(OrderState.DELIVERED, delivered!!.state)
            assertNotNull(delivered.deliveredAt)
        }
        
        @Test
        fun `cannot skip states`() {
            val order = createTestOrder()
            stateMachine.placeOrder(order)
            
            // Try to mark ready without accepting first
            val result = stateMachine.markReady(order.id)
            assertNull(result)
        }
        
        @Test
        fun `cannot transition from terminal state`() {
            val order = createTestOrder()
            stateMachine.placeOrder(order)
            stateMachine.rejectOrder(order.id, CancellationReason.CUSTOMER_REQUEST)
            
            val result = stateMachine.acceptOrder(order.id)
            assertNull(result)
        }
        
        @Test
        fun `cannot pickup without assigned agent`() {
            val order = createTestOrder()
            stateMachine.placeOrder(order)
            stateMachine.acceptOrder(order.id)
            stateMachine.startPreparing(order.id)
            stateMachine.markReady(order.id)
            
            val result = stateMachine.pickupOrder(order.id)
            assertNull(result)
        }
        
        @Test
        fun `observer receives state change events`() {
            val events = mutableListOf<Pair<Order, OrderState>>()
            
            stateMachine.addObserver(object : DeliveryObserver {
                override fun onOrderStateChanged(order: Order, previousState: OrderState) {
                    events.add(order to previousState)
                }
                override fun onAgentAssigned(order: Order, agent: DeliveryAgent) {}
                override fun onAgentLocationUpdated(orderId: String, location: Location) {}
                override fun onETAUpdated(orderId: String, eta: ETAEstimate) {}
            })
            
            val order = createTestOrder()
            stateMachine.placeOrder(order)
            stateMachine.acceptOrder(order.id)
            
            assertEquals(1, events.size)
            assertEquals(OrderState.PLACED, events[0].second)
        }
        
        @Test
        fun `getOrdersByState returns correct orders`() {
            val order1 = createTestOrder(id = "order-1")
            val order2 = createTestOrder(id = "order-2")
            
            stateMachine.placeOrder(order1)
            stateMachine.placeOrder(order2)
            stateMachine.acceptOrder("order-1")
            
            val placedOrders = stateMachine.getOrdersByState(OrderState.PLACED)
            val acceptedOrders = stateMachine.getOrdersByState(OrderState.ACCEPTED)
            
            assertEquals(1, placedOrders.size)
            assertEquals(1, acceptedOrders.size)
            assertEquals("order-2", placedOrders[0].id)
            assertEquals("order-1", acceptedOrders[0].id)
        }
    }
    
    @Nested
    inner class AssignmentStrategyTest {
        
        @Test
        fun `nearest agent strategy finds closest agent`() {
            val strategy = NearestAgentStrategy()
            val order = createTestOrder()
            
            val nearAgent = createTestAgent(
                id = "near",
                location = Location(37.7750, -122.4190)
            )
            val farAgent = createTestAgent(
                id = "far",
                location = Location(37.8, -122.5)
            )
            
            val result = strategy.findBestAgent(order, listOf(farAgent, nearAgent))
            
            assertNotNull(result)
            assertEquals("near", result!!.id)
        }
        
        @Test
        fun `nearest agent strategy filters unavailable agents`() {
            val strategy = NearestAgentStrategy()
            val order = createTestOrder()
            
            val unavailableAgent = createTestAgent(
                id = "unavailable",
                status = AgentStatus.DELIVERING
            )
            val availableAgent = createTestAgent(
                id = "available",
                location = Location(37.8, -122.5)
            )
            
            val result = strategy.findBestAgent(order, listOf(unavailableAgent, availableAgent))
            
            assertNotNull(result)
            assertEquals("available", result!!.id)
        }
        
        @Test
        fun `load balanced strategy distributes orders`() {
            val strategy = LoadBalancedStrategy(maxOrdersPerAgent = 3)
            val order = createTestOrder()
            
            val agent1 = createTestAgent(id = "agent-1")
            val agent2 = createTestAgent(id = "agent-2")
            
            strategy.findBestAgent(order, listOf(agent1, agent2))
            strategy.findBestAgent(order, listOf(agent1, agent2))
            val third = strategy.findBestAgent(order, listOf(agent1, agent2))
            
            // After 3 orders, they should be distributed across both agents
            assertNotNull(third)
            val total = strategy.getOrderCount("agent-1") + strategy.getOrderCount("agent-2")
            assertEquals(3, total)
            // Each agent should have at least 1 order
            assertTrue(strategy.getOrderCount("agent-1") >= 1)
            assertTrue(strategy.getOrderCount("agent-2") >= 1)
        }
        
        @Test
        fun `rating based strategy prefers higher rated agents`() {
            val strategy = RatingBasedStrategy(minRating = 4.0)
            val order = createTestOrder()
            
            val avgAgent = createTestAgent(id = "avg", rating = 4.2)
            val topAgent = createTestAgent(id = "top", rating = 4.9)
            
            val result = strategy.findBestAgent(order, listOf(avgAgent, topAgent))
            
            assertNotNull(result)
            assertEquals("top", result!!.id)
        }
        
        @Test
        fun `rating based strategy falls back if no qualified agents`() {
            val strategy = RatingBasedStrategy(minRating = 4.8)
            val order = createTestOrder()
            
            val lowRatedAgent = createTestAgent(id = "low", rating = 4.0)
            
            val result = strategy.findBestAgent(order, listOf(lowRatedAgent))
            
            assertNotNull(result)
            assertEquals("low", result!!.id)
        }
        
        @Test
        fun `time aware strategy uses different strategies`() {
            val rushHourStrategy = RatingBasedStrategy()
            val normalStrategy = NearestAgentStrategy()
            
            var currentTime = LocalDateTime.now().withHour(12)
            
            val strategy = TimeAwareStrategy(
                rushHourStrategy = rushHourStrategy,
                normalStrategy = normalStrategy,
                rushHourRanges = listOf(11 to 14),
                timeProvider = { currentTime }
            )
            
            val order = createTestOrder()
            val nearLowRated = createTestAgent(id = "near-low", rating = 4.0)
            val farHighRated = createTestAgent(
                id = "far-high",
                rating = 4.9,
                location = Location(37.79, -122.43)
            )
            
            val rushResult = strategy.findBestAgent(order, listOf(nearLowRated, farHighRated))
            assertEquals("far-high", rushResult!!.id)
            
            currentTime = currentTime.withHour(16)
            val normalResult = strategy.findBestAgent(order, listOf(nearLowRated, farHighRated))
            assertEquals("near-low", normalResult!!.id)
        }
        
        @Test
        fun `assignment service tracks agent status`() {
            val service = DeliveryAssignmentService(NearestAgentStrategy())
            val agent = createTestAgent()
            
            service.registerAgent(agent)
            assertEquals(1, service.getAvailableAgentCount())
            
            service.updateAgentStatus(agent.id, AgentStatus.DELIVERING)
            assertEquals(0, service.getAvailableAgentCount())
            
            service.releaseAgent(agent.id)
            assertEquals(1, service.getAvailableAgentCount())
        }
        
        @Test
        fun `assignment service returns assignment result`() {
            val service = DeliveryAssignmentService(NearestAgentStrategy())
            val agent = createTestAgent()
            service.registerAgent(agent)
            
            val order = createTestOrder()
            val result = service.findAgent(order)
            
            assertNotNull(result.agent)
            assertTrue(result.estimatedPickupMinutes >= 0)
            assertTrue(result.estimatedDeliveryMinutes >= 0)
        }
    }
    
    @Nested
    inner class LiveTrackingTest {
        
        private lateinit var trackingService: LiveTrackingService
        
        @BeforeEach
        fun setup() {
            trackingService = LiveTrackingService()
        }
        
        @Test
        fun `tracking starts when agent assigned`() {
            val order = createTestOrder()
            val agent = createTestAgent()
            
            trackingService.startTracking(order, agent)
            
            val tracking = trackingService.getTrackingInfo(order.id)
            assertNotNull(tracking)
            assertEquals(TrackingPhase.AGENT_TO_RESTAURANT, tracking!!.currentPhase)
        }
        
        @Test
        fun `location updates trigger ETA recalculation`() {
            val order = createTestOrder()
            val agent = createTestAgent()
            
            var etaUpdates = 0
            trackingService.addObserver(object : DeliveryObserver {
                override fun onOrderStateChanged(order: Order, previousState: OrderState) {}
                override fun onAgentAssigned(order: Order, agent: DeliveryAgent) {}
                override fun onAgentLocationUpdated(orderId: String, location: Location) {}
                override fun onETAUpdated(orderId: String, eta: ETAEstimate) {
                    etaUpdates++
                }
            })
            
            trackingService.startTracking(order, agent)
            trackingService.updateAgentLocation(agent.id, Location(37.7760, -122.4180))
            
            assertTrue(etaUpdates >= 1)
        }
        
        @Test
        fun `tracking phases transition correctly`() {
            val order = createTestOrder()
            val agent = createTestAgent()
            
            trackingService.startTracking(order, agent)
            assertEquals(TrackingPhase.AGENT_TO_RESTAURANT, trackingService.getTrackingInfo(order.id)!!.currentPhase)
            
            trackingService.agentArrivedAtRestaurant(order.id)
            assertEquals(TrackingPhase.AT_RESTAURANT, trackingService.getTrackingInfo(order.id)!!.currentPhase)
            
            trackingService.orderPickedUp(order.id)
            assertEquals(TrackingPhase.AGENT_TO_CUSTOMER, trackingService.getTrackingInfo(order.id)!!.currentPhase)
            
            trackingService.agentArrivedAtCustomer(order.id)
            assertEquals(TrackingPhase.AT_CUSTOMER, trackingService.getTrackingInfo(order.id)!!.currentPhase)
            
            trackingService.orderDelivered(order.id)
            assertNull(trackingService.getTrackingInfo(order.id))
        }
        
        @Test
        fun `ETA calculation varies by phase`() {
            val order = createTestOrder()
            val agent = createTestAgent()
            
            trackingService.startTracking(order, agent)
            val eta1 = trackingService.getCurrentETA(order.id)
            
            trackingService.orderPickedUp(order.id)
            val eta2 = trackingService.getCurrentETA(order.id)
            
            assertNotNull(eta1)
            assertNotNull(eta2)
            assertTrue(eta2!!.confidence > eta1!!.confidence)
        }
        
        @Test
        fun `customer notification observer sends notifications`() {
            val notificationService = MockCustomerNotificationService()
            val observer = CustomerNotificationObserver(notificationService)
            
            val order = createTestOrder()
            val agent = createTestAgent()
            
            observer.onAgentAssigned(order, agent)
            
            assertEquals(1, notificationService.notifications.size)
            assertTrue(notificationService.notifications[0].message.contains(agent.name))
        }
        
        @Test
        fun `real-time observer publishes updates`() {
            val publisher = MockRealTimeUpdatePublisher()
            val observer = RealTimeUpdatesObserver(publisher)
            
            val order = createTestOrder()
            val agent = createTestAgent()
            
            observer.onAgentAssigned(order, agent)
            observer.onAgentLocationUpdated(order.id, agentLocation)
            
            assertEquals(1, publisher.orderUpdates.size)
            assertEquals(1, publisher.locationUpdates.size)
        }
        
        @Test
        fun `analytics observer collects metrics`() {
            val observer = AnalyticsObserver()
            
            val order = createTestOrder()
            val agent = createTestAgent()
            
            observer.onAgentAssigned(order, agent)
            observer.onOrderStateChanged(
                order.copy(state = OrderState.DELIVERED, deliveredAt = LocalDateTime.now()),
                OrderState.IN_TRANSIT
            )
            
            val metrics = observer.getMetrics()
            assertEquals(1, metrics.totalDeliveries)
        }
    }
    
    @Nested
    inner class ModelTest {
        
        @Test
        fun `order state terminal check works`() {
            assertTrue(OrderState.DELIVERED.isTerminal())
            assertTrue(OrderState.CANCELLED.isTerminal())
            assertFalse(OrderState.PLACED.isTerminal())
            assertFalse(OrderState.IN_TRANSIT.isTerminal())
        }
        
        @Test
        fun `order state cancellation check works`() {
            assertTrue(OrderState.PLACED.canBeCancelled())
            assertTrue(OrderState.ACCEPTED.canBeCancelled())
            assertTrue(OrderState.PREPARING.canBeCancelled())
            assertFalse(OrderState.READY.canBeCancelled())
            assertFalse(OrderState.PICKED_UP.canBeCancelled())
        }
        
        @Test
        fun `location distance calculation is accurate`() {
            val distance = restaurantLocation.distanceTo(customerLocation)
            
            assertTrue(distance > 0)
            assertTrue(distance < 5)
        }
        
        @Test
        fun `location within radius check works`() {
            val nearby = Location(37.7750, -122.4190)
            val faraway = Location(38.0, -123.0)
            
            assertTrue(nearby.isWithinRadius(restaurantLocation, 1.0))
            assertFalse(faraway.isWithinRadius(restaurantLocation, 1.0))
        }
        
        @Test
        fun `order total calculation is correct`() {
            val restaurant = createTestRestaurant()
            val customer = createTestCustomer()
            val menuItem = createTestMenuItem(restaurant.id, price = 10.0)
            val orderItem = OrderItem(menuItem = menuItem, quantity = 2)
            
            val order = Order(
                customer = customer,
                restaurant = restaurant,
                items = listOf(orderItem),
                tip = 5.0
            )
            
            assertEquals(20.0, order.subtotal, 0.01)
            assertTrue(order.deliveryFee > 0)
            assertTrue(order.total > order.subtotal)
        }
        
        @Test
        fun `agent availability check works`() {
            val availableAgent = createTestAgent(status = AgentStatus.AVAILABLE)
            val busyAgent = createTestAgent(status = AgentStatus.DELIVERING)
            
            assertTrue(availableAgent.isAvailable())
            assertFalse(busyAgent.isAvailable())
        }
        
        @Test
        fun `vehicle type constraints are correct`() {
            assertTrue(VehicleType.BIKE.maxDistanceKm < VehicleType.MOTORCYCLE.maxDistanceKm)
            assertTrue(VehicleType.MOTORCYCLE.maxDistanceKm < VehicleType.CAR.maxDistanceKm)
            assertTrue(VehicleType.BIKE.speedKmh < VehicleType.MOTORCYCLE.speedKmh)
        }
        
        @Test
        fun `ETA estimate contains all phases`() {
            val eta = ETAEstimate(
                estimatedTime = LocalDateTime.now().plusMinutes(45),
                preparationMinutes = 15,
                pickupMinutes = 10,
                deliveryMinutes = 20,
                confidence = 0.8
            )
            
            assertEquals(45, eta.totalMinutes)
            assertTrue(eta.confidence in 0.0..1.0)
        }
        
        @Test
        fun `restaurant opening hours work`() {
            val openingHours = OpeningHours(
                monday = TimeRange(
                    java.time.LocalTime.of(9, 0),
                    java.time.LocalTime.of(22, 0)
                )
            )
            
            val mondayNoon = LocalDateTime.now()
                .with(java.time.DayOfWeek.MONDAY)
                .withHour(12)
            
            val mondayEarly = LocalDateTime.now()
                .with(java.time.DayOfWeek.MONDAY)
                .withHour(7)
            
            assertTrue(openingHours.isOpenAt(mondayNoon))
            assertFalse(openingHours.isOpenAt(mondayEarly))
        }
    }
}
