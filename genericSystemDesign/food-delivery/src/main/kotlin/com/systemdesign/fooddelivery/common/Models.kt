package com.systemdesign.fooddelivery.common

import java.time.LocalDateTime
import java.util.UUID
import kotlin.math.*

/**
 * Core domain models for Food Delivery System.
 * 
 * Extensibility Points:
 * - New order states: Add to OrderState enum and update state machine
 * - New assignment strategies: Implement DeliveryAssignmentStrategy interface
 * - New tracking events: Add to DeliveryEvent sealed class
 * - New cuisines/categories: Add to CuisineType enum
 * 
 * Breaking Changes Required For:
 * - Changing order ID structure
 * - Modifying delivery flow fundamentally
 */

/** Order lifecycle states */
enum class OrderState {
    PLACED,
    ACCEPTED,
    PREPARING,
    READY,
    PICKED_UP,
    IN_TRANSIT,
    DELIVERED,
    CANCELLED;
    
    fun isTerminal(): Boolean = this in listOf(DELIVERED, CANCELLED)
    fun isActive(): Boolean = !isTerminal()
    fun canBeCancelled(): Boolean = this in listOf(PLACED, ACCEPTED, PREPARING)
}

/** Cuisine types for restaurants */
enum class CuisineType {
    ITALIAN,
    CHINESE,
    INDIAN,
    JAPANESE,
    MEXICAN,
    AMERICAN,
    THAI,
    MEDITERRANEAN,
    FAST_FOOD,
    HEALTHY,
    DESSERTS,
    BEVERAGES
}

/** Delivery agent status */
enum class AgentStatus {
    AVAILABLE,
    ASSIGNED,
    PICKING_UP,
    DELIVERING,
    OFFLINE,
    ON_BREAK
}

/** Cancellation reasons */
enum class CancellationReason {
    CUSTOMER_REQUEST,
    RESTAURANT_REJECTED,
    RESTAURANT_CLOSED,
    OUT_OF_STOCK,
    NO_DELIVERY_AGENT,
    AGENT_CANCELLED,
    PAYMENT_FAILED,
    TIMEOUT,
    SYSTEM_ERROR
}

/** Geographic location */
data class Location(
    val lat: Double,
    val lng: Double,
    val address: String? = null
) {
    fun distanceTo(other: Location): Double {
        val earthRadius = 6371.0
        val dLat = Math.toRadians(other.lat - lat)
        val dLng = Math.toRadians(other.lng - lng)
        val a = sin(dLat / 2).pow(2) + 
                cos(Math.toRadians(lat)) * cos(Math.toRadians(other.lat)) * 
                sin(dLng / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }
    
    fun isWithinRadius(other: Location, radiusKm: Double): Boolean {
        return distanceTo(other) <= radiusKm
    }
}

/** Restaurant details */
data class Restaurant(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val location: Location,
    val cuisineTypes: Set<CuisineType>,
    val rating: Double = 0.0,
    val totalRatings: Int = 0,
    val isOpen: Boolean = true,
    val averagePrepTime: Int = 20,
    val openingHours: OpeningHours? = null
) {
    fun isCurrentlyOpen(currentTime: LocalDateTime = LocalDateTime.now()): Boolean {
        if (!isOpen) return false
        return openingHours?.isOpenAt(currentTime) ?: true
    }
}

/** Operating hours */
data class OpeningHours(
    val monday: TimeRange? = null,
    val tuesday: TimeRange? = null,
    val wednesday: TimeRange? = null,
    val thursday: TimeRange? = null,
    val friday: TimeRange? = null,
    val saturday: TimeRange? = null,
    val sunday: TimeRange? = null
) {
    fun isOpenAt(dateTime: LocalDateTime): Boolean {
        val dayRange = when (dateTime.dayOfWeek) {
            java.time.DayOfWeek.MONDAY -> monday
            java.time.DayOfWeek.TUESDAY -> tuesday
            java.time.DayOfWeek.WEDNESDAY -> wednesday
            java.time.DayOfWeek.THURSDAY -> thursday
            java.time.DayOfWeek.FRIDAY -> friday
            java.time.DayOfWeek.SATURDAY -> saturday
            java.time.DayOfWeek.SUNDAY -> sunday
        }
        return dayRange?.contains(dateTime.toLocalTime()) ?: false
    }
}

/** Time range for opening hours */
data class TimeRange(
    val open: java.time.LocalTime,
    val close: java.time.LocalTime
) {
    fun contains(time: java.time.LocalTime): Boolean {
        return if (close > open) {
            time >= open && time < close
        } else {
            time >= open || time < close
        }
    }
}

/** Menu item */
data class MenuItem(
    val id: String = UUID.randomUUID().toString(),
    val restaurantId: String,
    val name: String,
    val description: String,
    val price: Double,
    val category: String,
    val isAvailable: Boolean = true,
    val preparationTime: Int = 10,
    val imageUrl: String? = null,
    val isVegetarian: Boolean = false,
    val isVegan: Boolean = false,
    val allergens: Set<String> = emptySet()
)

/** Restaurant menu */
data class Menu(
    val restaurantId: String,
    val items: List<MenuItem>,
    val categories: List<String>
) {
    fun getAvailableItems(): List<MenuItem> = items.filter { it.isAvailable }
    
    fun getItemsByCategory(category: String): List<MenuItem> = 
        items.filter { it.category == category && it.isAvailable }
}

/** Item in an order */
data class OrderItem(
    val id: String = UUID.randomUUID().toString(),
    val menuItem: MenuItem,
    val quantity: Int,
    val specialInstructions: String? = null,
    val addons: List<Addon> = emptyList()
) {
    val subtotal: Double
        get() = (menuItem.price + addons.sumOf { it.price }) * quantity
}

/** Addon for menu items */
data class Addon(
    val id: String,
    val name: String,
    val price: Double
)

/** Customer details */
data class Customer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val phone: String,
    val email: String? = null,
    val deliveryAddress: Location,
    val rating: Double = 5.0
)

/** Delivery agent */
data class DeliveryAgent(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val phone: String,
    val location: Location,
    val status: AgentStatus = AgentStatus.AVAILABLE,
    val rating: Double = 5.0,
    val totalDeliveries: Int = 0,
    val vehicleType: VehicleType = VehicleType.BIKE,
    val currentOrderId: String? = null
) {
    fun isAvailable(): Boolean = status == AgentStatus.AVAILABLE
    
    fun distanceTo(location: Location): Double = this.location.distanceTo(location)
    
    fun withLocation(newLocation: Location): DeliveryAgent = copy(location = newLocation)
    fun withStatus(newStatus: AgentStatus): DeliveryAgent = copy(status = newStatus)
    fun withCurrentOrder(orderId: String?): DeliveryAgent = copy(currentOrderId = orderId)
}

/** Vehicle types */
enum class VehicleType(val maxDistanceKm: Double, val speedKmh: Double) {
    BIKE(5.0, 15.0),
    MOTORCYCLE(15.0, 30.0),
    CAR(25.0, 40.0)
}

/** Complete order */
data class Order(
    val id: String = UUID.randomUUID().toString(),
    val customer: Customer,
    val restaurant: Restaurant,
    val items: List<OrderItem>,
    val state: OrderState = OrderState.PLACED,
    val deliveryAgent: DeliveryAgent? = null,
    val placedAt: LocalDateTime = LocalDateTime.now(),
    val acceptedAt: LocalDateTime? = null,
    val preparedAt: LocalDateTime? = null,
    val pickedUpAt: LocalDateTime? = null,
    val deliveredAt: LocalDateTime? = null,
    val cancelledAt: LocalDateTime? = null,
    val cancellationReason: CancellationReason? = null,
    val estimatedDeliveryTime: LocalDateTime? = null,
    val deliveryInstructions: String? = null,
    val tip: Double = 0.0
) {
    val subtotal: Double
        get() = items.sumOf { it.subtotal }
    
    val deliveryFee: Double
        get() = calculateDeliveryFee()
    
    val total: Double
        get() = subtotal + deliveryFee + tip
    
    val estimatedPrepTime: Int
        get() = maxOf(restaurant.averagePrepTime, items.maxOf { it.menuItem.preparationTime })
    
    private fun calculateDeliveryFee(): Double {
        val distance = restaurant.location.distanceTo(customer.deliveryAddress)
        return when {
            distance < 2.0 -> 2.0
            distance < 5.0 -> 3.5
            distance < 10.0 -> 5.0
            else -> 5.0 + (distance - 10) * 0.5
        }
    }
    
    fun isTerminal(): Boolean = state.isTerminal()
    fun isActive(): Boolean = state.isActive()
    
    fun withState(newState: OrderState): Order = copy(state = newState)
    fun withAgent(agent: DeliveryAgent?): Order = copy(deliveryAgent = agent)
}

/** ETA calculation result */
data class ETAEstimate(
    val estimatedTime: LocalDateTime,
    val preparationMinutes: Int,
    val pickupMinutes: Int,
    val deliveryMinutes: Int,
    val confidence: Double
) {
    val totalMinutes: Int
        get() = preparationMinutes + pickupMinutes + deliveryMinutes
}

/** Observer interface for delivery events */
interface DeliveryObserver {
    fun onOrderStateChanged(order: Order, previousState: OrderState)
    fun onAgentAssigned(order: Order, agent: DeliveryAgent)
    fun onAgentLocationUpdated(orderId: String, location: Location)
    fun onETAUpdated(orderId: String, eta: ETAEstimate)
}

/** Strategy interface for delivery agent assignment */
interface DeliveryAssignmentStrategy {
    fun findBestAgent(
        order: Order,
        availableAgents: List<DeliveryAgent>
    ): DeliveryAgent?
}

/** Delivery event sealed class for tracking */
sealed class DeliveryEvent {
    abstract val orderId: String
    abstract val timestamp: LocalDateTime
    
    data class OrderPlaced(
        override val orderId: String,
        val customerId: String,
        val restaurantId: String,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : DeliveryEvent()
    
    data class OrderAccepted(
        override val orderId: String,
        val restaurantId: String,
        val estimatedPrepTime: Int,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : DeliveryEvent()
    
    data class OrderRejected(
        override val orderId: String,
        val restaurantId: String,
        val reason: CancellationReason,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : DeliveryEvent()
    
    data class PreparationStarted(
        override val orderId: String,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : DeliveryEvent()
    
    data class OrderReady(
        override val orderId: String,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : DeliveryEvent()
    
    data class AgentAssigned(
        override val orderId: String,
        val agentId: String,
        val estimatedPickupTime: Int,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : DeliveryEvent()
    
    data class AgentReassigned(
        override val orderId: String,
        val previousAgentId: String,
        val newAgentId: String,
        val reason: String,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : DeliveryEvent()
    
    data class OrderPickedUp(
        override val orderId: String,
        val agentId: String,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : DeliveryEvent()
    
    data class LocationUpdate(
        override val orderId: String,
        val agentId: String,
        val location: Location,
        val etaMinutes: Int,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : DeliveryEvent()
    
    data class OrderDelivered(
        override val orderId: String,
        val agentId: String,
        val deliveryDurationMinutes: Int,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : DeliveryEvent()
    
    data class OrderCancelled(
        override val orderId: String,
        val reason: CancellationReason,
        val cancelledBy: String,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : DeliveryEvent()
    
    data class CustomerRating(
        override val orderId: String,
        val restaurantRating: Int?,
        val deliveryRating: Int?,
        val feedback: String?,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : DeliveryEvent()
}

/** Rating for completed delivery */
data class DeliveryRating(
    val orderId: String,
    val restaurantRating: Int?,
    val deliveryRating: Int?,
    val foodRating: Int?,
    val feedback: String?,
    val createdAt: LocalDateTime = LocalDateTime.now()
) {
    init {
        require(restaurantRating == null || restaurantRating in 1..5)
        require(deliveryRating == null || deliveryRating in 1..5)
        require(foodRating == null || foodRating in 1..5)
    }
}

/** Order summary for history */
data class OrderSummary(
    val orderId: String,
    val restaurantName: String,
    val itemCount: Int,
    val total: Double,
    val state: OrderState,
    val placedAt: LocalDateTime,
    val deliveredAt: LocalDateTime?
)

/** Delivery metrics */
data class DeliveryMetrics(
    val averageDeliveryTime: Int,
    val onTimeDeliveryRate: Double,
    val averageRating: Double,
    val totalDeliveries: Int
)
