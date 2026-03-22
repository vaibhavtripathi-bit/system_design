package com.systemdesign.hotelreservation.approach_01_strategy_allocation

import com.systemdesign.hotelreservation.common.*
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Approach 1: Strategy Pattern for Room Allocation
 * 
 * Different room allocation strategies can be plugged in based on business needs.
 * The hotel reservation system delegates room selection to the configured strategy.
 * 
 * Pattern: Strategy
 * 
 * Trade-offs:
 * + Easy to add new allocation algorithms without changing core logic
 * + Strategies can be swapped at runtime
 * + Each strategy is testable in isolation
 * - Additional abstraction layer
 * - Strategy selection logic needed
 * 
 * When to use:
 * - When multiple algorithms exist for the same task
 * - When algorithm selection should be configurable
 * - When you want to isolate algorithm implementation details
 * 
 * Extensibility:
 * - New allocation strategy: Implement RoomAllocationStrategy interface
 * - New criteria: Extend GuestPreferences and update strategies
 */

interface RoomAllocationStrategy {
    val name: String
    
    fun allocateRoom(
        request: ReservationRequest,
        availableRooms: List<Room>
    ): Room?
    
    fun rankRooms(
        request: ReservationRequest,
        availableRooms: List<Room>
    ): List<Pair<Room, Int>>
}

/**
 * First Available Strategy: Simply returns the first room that matches the type.
 * Fast but doesn't optimize for guest satisfaction.
 */
class FirstAvailableStrategy : RoomAllocationStrategy {
    override val name = "First Available"
    
    override fun allocateRoom(request: ReservationRequest, availableRooms: List<Room>): Room? {
        return availableRooms
            .filter { it.type == request.roomType }
            .filter { it.maxOccupancy >= request.numberOfGuests }
            .firstOrNull()
    }
    
    override fun rankRooms(request: ReservationRequest, availableRooms: List<Room>): List<Pair<Room, Int>> {
        return availableRooms
            .filter { it.type == request.roomType }
            .filter { it.maxOccupancy >= request.numberOfGuests }
            .map { it to 1 }
    }
}

/**
 * Best Fit Strategy: Allocates the room that best fits the guest count.
 * Minimizes wasted capacity.
 */
class BestFitStrategy : RoomAllocationStrategy {
    override val name = "Best Fit"
    
    override fun allocateRoom(request: ReservationRequest, availableRooms: List<Room>): Room? {
        return availableRooms
            .filter { it.type == request.roomType }
            .filter { it.maxOccupancy >= request.numberOfGuests }
            .minByOrNull { it.maxOccupancy - request.numberOfGuests }
    }
    
    override fun rankRooms(request: ReservationRequest, availableRooms: List<Room>): List<Pair<Room, Int>> {
        return availableRooms
            .filter { it.type == request.roomType }
            .filter { it.maxOccupancy >= request.numberOfGuests }
            .map { room ->
                val wastedCapacity = room.maxOccupancy - request.numberOfGuests
                val score = 100 - (wastedCapacity * 10)
                room to score
            }
            .sortedByDescending { it.second }
    }
}

/**
 * Preference Based Strategy: Scores rooms based on guest preferences.
 * Maximizes guest satisfaction.
 */
class PreferenceBasedStrategy : RoomAllocationStrategy {
    override val name = "Preference Based"
    
    override fun allocateRoom(request: ReservationRequest, availableRooms: List<Room>): Room? {
        return rankRooms(request, availableRooms).firstOrNull()?.first
    }
    
    override fun rankRooms(request: ReservationRequest, availableRooms: List<Room>): List<Pair<Room, Int>> {
        val preferences = request.preferences
        
        return availableRooms
            .filter { it.type == request.roomType }
            .filter { it.maxOccupancy >= request.numberOfGuests }
            .filter { room ->
                preferences.requiredAmenities.all { room.hasAmenity(it) }
            }
            .map { room ->
                var score = room.matchesPreferences(preferences)
                
                if (request.guest.loyaltyTier >= LoyaltyTier.GOLD) {
                    if (room.floor >= 5) score += 15
                }
                
                room to score
            }
            .sortedByDescending { it.second }
    }
}

/**
 * Upgrade Strategy: Tries to give guests a free upgrade when possible.
 * Best for loyalty programs.
 */
class UpgradeStrategy(private val fallback: RoomAllocationStrategy) : RoomAllocationStrategy {
    override val name = "Upgrade"
    
    private val upgradeOrder = listOf(
        RoomType.SINGLE to RoomType.DOUBLE,
        RoomType.DOUBLE to RoomType.SUITE,
        RoomType.SUITE to RoomType.PENTHOUSE
    ).toMap()
    
    override fun allocateRoom(request: ReservationRequest, availableRooms: List<Room>): Room? {
        if (request.guest.loyaltyTier < LoyaltyTier.GOLD) {
            return fallback.allocateRoom(request, availableRooms)
        }
        
        val upgradedType = upgradeOrder[request.roomType]
        if (upgradedType != null) {
            val upgradedRequest = request.copy(roomType = upgradedType)
            val upgraded = fallback.allocateRoom(upgradedRequest, availableRooms)
            if (upgraded != null) return upgraded
        }
        
        return fallback.allocateRoom(request, availableRooms)
    }
    
    override fun rankRooms(request: ReservationRequest, availableRooms: List<Room>): List<Pair<Room, Int>> {
        val baseRanking = fallback.rankRooms(request, availableRooms)
        
        if (request.guest.loyaltyTier < LoyaltyTier.GOLD) {
            return baseRanking
        }
        
        val upgradedType = upgradeOrder[request.roomType]
        if (upgradedType != null) {
            val upgradedRequest = request.copy(roomType = upgradedType)
            val upgradedRanking = fallback.rankRooms(upgradedRequest, availableRooms)
                .map { (room, score) -> room to score + 50 }
            
            return (upgradedRanking + baseRanking).sortedByDescending { it.second }
        }
        
        return baseRanking
    }
}

/**
 * Price Optimized Strategy: Balances guest preferences with revenue optimization.
 */
class PriceOptimizedStrategy : RoomAllocationStrategy {
    override val name = "Price Optimized"
    
    override fun allocateRoom(request: ReservationRequest, availableRooms: List<Room>): Room? {
        return rankRooms(request, availableRooms).firstOrNull()?.first
    }
    
    override fun rankRooms(request: ReservationRequest, availableRooms: List<Room>): List<Pair<Room, Int>> {
        val avgPrice = availableRooms
            .filter { it.type == request.roomType }
            .map { it.pricePerNight }
            .average()
            .takeIf { !it.isNaN() } ?: 0.0
        
        return availableRooms
            .filter { it.type == request.roomType }
            .filter { it.maxOccupancy >= request.numberOfGuests }
            .map { room ->
                var score = room.matchesPreferences(request.preferences)
                
                val priceDiff = room.pricePerNight - avgPrice
                score += when {
                    priceDiff > 0 -> 10
                    priceDiff < 0 -> -5
                    else -> 0
                }
                
                room to score
            }
            .sortedByDescending { it.second }
    }
}

class StrategyBasedHotelReservation(
    private val hotel: Hotel,
    private var allocationStrategy: RoomAllocationStrategy = FirstAvailableStrategy()
) {
    private val reservations = ConcurrentHashMap<String, Reservation>()
    private val roomReservations = ConcurrentHashMap<String, MutableList<Reservation>>()
    
    fun setAllocationStrategy(strategy: RoomAllocationStrategy) {
        this.allocationStrategy = strategy
    }
    
    fun getStrategyName(): String = allocationStrategy.name
    
    fun makeReservation(request: ReservationRequest): ReservationResult {
        if (request.numberOfGuests < 1) {
            return ReservationResult.InvalidRequest("Number of guests must be at least 1")
        }
        
        if (request.dates.nights < 1) {
            return ReservationResult.InvalidRequest("Reservation must be for at least 1 night")
        }
        
        if (request.dates.checkIn.isBefore(LocalDate.now())) {
            return ReservationResult.InvalidRequest("Check-in date cannot be in the past")
        }
        
        // Pass all available rooms - let strategy handle type filtering/upgrading
        val allAvailableRooms = getAvailableRooms(request.dates, null)
        val requestedTypeRooms = allAvailableRooms.filter { it.type == request.roomType }
        
        if (requestedTypeRooms.isEmpty() && !allAvailableRooms.any { it.type.ordinal > request.roomType.ordinal }) {
            return ReservationResult.NoAvailability(request.dates, request.roomType)
        }
        
        val selectedRoom = allocationStrategy.allocateRoom(request, allAvailableRooms)
            ?: return ReservationResult.NoAvailability(request.dates, request.roomType)
        
        val reservation = Reservation(
            id = UUID.randomUUID().toString(),
            guest = request.guest,
            room = selectedRoom,
            dates = request.dates,
            status = ReservationStatus.PENDING,
            specialRequests = request.specialRequests,
            numberOfGuests = request.numberOfGuests
        )
        
        reservations[reservation.id] = reservation
        roomReservations.getOrPut(selectedRoom.number) { mutableListOf() }.add(reservation)
        
        return ReservationResult.Success(reservation)
    }
    
    fun getAvailableRooms(dates: DateRange, roomType: RoomType? = null): List<Room> {
        return hotel.rooms
            .filter { roomType == null || it.type == roomType }
            .filter { room -> isRoomAvailable(room.number, dates) }
    }
    
    fun isRoomAvailable(roomNumber: String, dates: DateRange): Boolean {
        val existingReservations = roomReservations[roomNumber] ?: return true
        
        return existingReservations
            .filter { it.isActive() }
            .none { it.dates.overlaps(dates) }
    }
    
    fun getReservation(id: String): Reservation? = reservations[id]
    
    fun getReservationsForGuest(guestId: String): List<Reservation> {
        return reservations.values.filter { it.guest.id == guestId }
    }
    
    fun getReservationsForRoom(roomNumber: String): List<Reservation> {
        return roomReservations[roomNumber]?.toList() ?: emptyList()
    }
    
    fun getReservationsForDate(date: LocalDate): List<Reservation> {
        return reservations.values.filter { it.dates.contains(date) && it.isActive() }
    }
    
    fun cancelReservation(reservationId: String): StatusChangeResult {
        val reservation = reservations[reservationId]
            ?: return StatusChangeResult.ValidationFailed("Reservation not found")
        
        if (reservation.status == ReservationStatus.CHECKED_IN) {
            return StatusChangeResult.InvalidTransition(
                reservation.status,
                ReservationStatus.CANCELLED
            )
        }
        
        if (reservation.status == ReservationStatus.CHECKED_OUT) {
            return StatusChangeResult.InvalidTransition(
                reservation.status,
                ReservationStatus.CANCELLED
            )
        }
        
        reservation.status = ReservationStatus.CANCELLED
        return StatusChangeResult.Success(reservation)
    }
    
    fun confirmReservation(reservationId: String): StatusChangeResult {
        val reservation = reservations[reservationId]
            ?: return StatusChangeResult.ValidationFailed("Reservation not found")
        
        if (reservation.status != ReservationStatus.PENDING) {
            return StatusChangeResult.InvalidTransition(
                reservation.status,
                ReservationStatus.CONFIRMED
            )
        }
        
        reservation.status = ReservationStatus.CONFIRMED
        reservation.paymentConfirmed = true
        return StatusChangeResult.Success(reservation)
    }
    
    fun getRoomRankings(request: ReservationRequest): List<Pair<Room, Int>> {
        val availableRooms = getAvailableRooms(request.dates, request.roomType)
        return allocationStrategy.rankRooms(request, availableRooms)
    }
    
    fun getOccupancyRate(date: LocalDate): Double {
        val totalRooms = hotel.rooms.size
        if (totalRooms == 0) return 0.0
        
        val occupiedRooms = reservations.values.count { 
            it.dates.contains(date) && it.isActive() 
        }
        
        return (occupiedRooms.toDouble() / totalRooms) * 100
    }
    
    fun getStatistics(): HotelStatistics {
        val today = LocalDate.now()
        val activeReservations = reservations.values.filter { it.dates.contains(today) && it.isActive() }
        
        return HotelStatistics(
            totalRooms = hotel.rooms.size,
            occupiedRooms = activeReservations.size,
            availableRooms = hotel.rooms.size - activeReservations.size,
            occupancyRate = getOccupancyRate(today),
            reservationsByStatus = reservations.values.groupingBy { it.status }.eachCount(),
            revenueToday = activeReservations.sumOf { it.room.pricePerNight }
        )
    }
}
