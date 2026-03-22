package com.systemdesign.moviebooking.approach_02_strategy_selection

import com.systemdesign.moviebooking.common.*
import kotlin.math.abs

/**
 * Approach 2: Strategy Pattern for Seat Selection
 * 
 * Different seat selection algorithms can be swapped at runtime based on user
 * preferences, theater policies, or accessibility requirements.
 * 
 * Pattern: Strategy Pattern
 * 
 * Trade-offs:
 * + Different selection algorithms without modifying booking code
 * + Easy to add new strategies (A/B testing, promotions, etc.)
 * + Strategies are testable in isolation
 * - Additional abstraction layer
 * - Strategy selection logic can become complex
 * 
 * When to use:
 * - When multiple seat selection algorithms are needed
 * - When selection logic may change based on context
 * - When you want to A/B test different selection approaches
 * 
 * Extensibility:
 * - Add new strategy: Implement SeatSelectionStrategy interface
 * - Example strategies to add:
 *   - SocialDistancingStrategy - maintains spacing between groups
 *   - GroupProximityStrategy - seats groups near each other
 *   - PriceOptimizedStrategy - finds best value seats
 *   - ViewQualityStrategy - prioritizes center seats with good viewing angles
 */

/**
 * Finds the best available connected seats automatically.
 * Prioritizes:
 * 1. Seats in preferred rows (middle rows are usually best)
 * 2. Contiguous seats for groups
 * 3. Center positions for better viewing
 */
class BestAvailableStrategy(
    private val preferMiddleRows: Boolean = true,
    private val preferCenter: Boolean = true
) : SeatSelectionStrategy {
    
    override fun selectSeats(
        availableSeats: List<ShowSeat>,
        count: Int,
        preferences: SeatPreferences
    ): List<Seat> {
        if (availableSeats.size < count) return emptyList()
        
        val filteredSeats = filterByPreferences(availableSeats, preferences)
        if (filteredSeats.size < count) return emptyList()
        
        val seatsByRow = filteredSeats.groupBy { it.seat.row }
        
        if (preferences.requireContiguous) {
            return findContiguousSeats(seatsByRow, count, preferences) ?: emptyList()
        }
        
        return selectBestIndividualSeats(filteredSeats, count)
    }
    
    private fun filterByPreferences(
        seats: List<ShowSeat>,
        preferences: SeatPreferences
    ): List<ShowSeat> {
        var filtered = seats.filter { it.isAvailable() }
        
        preferences.preferredType?.let { type ->
            val typeFiltered = filtered.filter { it.seat.type == type }
            if (typeFiltered.size >= 1) filtered = typeFiltered
        }
        
        if (preferences.preferredRows.isNotEmpty()) {
            val rowFiltered = filtered.filter { it.seat.row in preferences.preferredRows }
            if (rowFiltered.isNotEmpty()) filtered = rowFiltered
        }
        
        return filtered
    }
    
    private fun findContiguousSeats(
        seatsByRow: Map<Char, List<ShowSeat>>,
        count: Int,
        preferences: SeatPreferences
    ): List<Seat>? {
        val sortedRows = if (preferMiddleRows) {
            val allRows = seatsByRow.keys.sorted()
            val middleIndex = allRows.size / 2
            allRows.sortedBy { abs(allRows.indexOf(it) - middleIndex) }
        } else {
            seatsByRow.keys.sorted()
        }
        
        for (row in sortedRows) {
            val rowSeats = seatsByRow[row] ?: continue
            val contiguous = findContiguousInRow(rowSeats, count)
            if (contiguous != null) {
                return if (preferCenter) {
                    selectCenterGroup(contiguous, count)
                } else {
                    contiguous.take(count).map { it.seat }
                }
            }
        }
        
        return null
    }
    
    private fun findContiguousInRow(seats: List<ShowSeat>, count: Int): List<ShowSeat>? {
        val sorted = seats.sortedBy { it.seat.number }
        var start = 0
        
        while (start <= sorted.size - count) {
            var isContiguous = true
            for (i in start until start + count - 1) {
                if (sorted[i + 1].seat.number != sorted[i].seat.number + 1) {
                    isContiguous = false
                    start = i + 1
                    break
                }
            }
            if (isContiguous) {
                return sorted.subList(start, start + count)
            }
        }
        
        return null
    }
    
    private fun selectCenterGroup(contiguousGroups: List<ShowSeat>, count: Int): List<Seat> {
        val sorted = contiguousGroups.sortedBy { it.seat.number }
        val maxSeatNum = sorted.maxOfOrNull { it.seat.number } ?: 0
        val centerPoint = maxSeatNum / 2.0
        
        var bestGroup: List<ShowSeat>? = null
        var bestDistance = Double.MAX_VALUE
        
        for (start in 0..sorted.size - count) {
            val group = sorted.subList(start, start + count)
            val groupCenter = group.sumOf { it.seat.number } / count.toDouble()
            val distance = abs(groupCenter - centerPoint)
            if (distance < bestDistance) {
                bestDistance = distance
                bestGroup = group
            }
        }
        
        return bestGroup?.map { it.seat } ?: emptyList()
    }
    
    private fun selectBestIndividualSeats(seats: List<ShowSeat>, count: Int): List<Seat> {
        val scored = seats.map { showSeat ->
            val rowScore = if (preferMiddleRows) scoreRowPosition(showSeat.seat.row) else 0.0
            val centerScore = if (preferCenter) scoreCenterPosition(showSeat.seat.number, 20) else 0.0
            val typeScore = showSeat.seat.type.priceMultiplier
            showSeat to (rowScore + centerScore + typeScore)
        }
        
        return scored
            .sortedByDescending { it.second }
            .take(count)
            .map { it.first.seat }
    }
    
    private fun scoreRowPosition(row: Char): Double {
        val rowIndex = row - 'A'
        val middleRow = 7
        return 10.0 - abs(rowIndex - middleRow)
    }
    
    private fun scoreCenterPosition(seatNumber: Int, seatsPerRow: Int): Double {
        val center = seatsPerRow / 2.0
        return 10.0 - abs(seatNumber - center)
    }
}

/**
 * Allows users to manually select specific seats.
 * Validates that selected seats are available and optionally contiguous.
 */
class ManualSelectionStrategy(
    private val enforceContiguous: Boolean = false
) : SeatSelectionStrategy {
    
    override fun selectSeats(
        availableSeats: List<ShowSeat>,
        count: Int,
        preferences: SeatPreferences
    ): List<Seat> {
        val requestedSeats = preferences.preferredRows
            .flatMap { row -> 
                availableSeats.filter { it.seat.row == row && it.isAvailable() }
            }
            .take(count)
        
        if (requestedSeats.size != count) {
            return emptyList()
        }
        
        if (enforceContiguous || preferences.requireContiguous) {
            if (!areContiguous(requestedSeats)) {
                return emptyList()
            }
        }
        
        return requestedSeats.map { it.seat }
    }
    
    /**
     * Select specific seats by ID
     */
    fun selectSpecificSeats(
        availableSeats: List<ShowSeat>,
        requestedSeatIds: List<String>
    ): SelectionResult {
        val availableMap = availableSeats.associateBy { it.seat.id }
        val selected = mutableListOf<Seat>()
        val unavailable = mutableListOf<String>()
        
        for (seatId in requestedSeatIds) {
            val showSeat = availableMap[seatId]
            when {
                showSeat == null -> unavailable.add(seatId)
                !showSeat.isAvailable() -> unavailable.add(seatId)
                else -> selected.add(showSeat.seat)
            }
        }
        
        return if (unavailable.isEmpty()) {
            SelectionResult.Success(selected)
        } else {
            SelectionResult.PartiallyUnavailable(selected, unavailable)
        }
    }
    
    private fun areContiguous(seats: List<ShowSeat>): Boolean {
        if (seats.size <= 1) return true
        
        val byRow = seats.groupBy { it.seat.row }
        if (byRow.size > 1) return false
        
        val sorted = seats.sortedBy { it.seat.number }
        for (i in 0 until sorted.size - 1) {
            if (sorted[i + 1].seat.number != sorted[i].seat.number + 1) {
                return false
            }
        }
        return true
    }
}

/** Result of manual seat selection */
sealed class SelectionResult {
    data class Success(val seats: List<Seat>) : SelectionResult()
    data class PartiallyUnavailable(
        val available: List<Seat>,
        val unavailable: List<String>
    ) : SelectionResult()
}

/**
 * Prioritizes wheelchair-accessible seating and companion seats.
 * Ensures accessible seats are allocated appropriately.
 */
class AccessibleFirstStrategy(
    private val reserveCompanionSeats: Boolean = true,
    private val companionSeatsPerWheelchair: Int = 1
) : SeatSelectionStrategy {
    
    override fun selectSeats(
        availableSeats: List<ShowSeat>,
        count: Int,
        preferences: SeatPreferences
    ): List<Seat> {
        val available = availableSeats.filter { it.isAvailable() }
        
        if (preferences.accessibilityRequired) {
            return selectAccessibleSeats(available, count)
        }
        
        return selectNonAccessibleSeats(available, count, preferences)
    }
    
    private fun selectAccessibleSeats(available: List<ShowSeat>, count: Int): List<Seat> {
        val wheelchairSeats = available.filter { it.seat.type == SeatType.WHEELCHAIR }
        val result = mutableListOf<Seat>()
        
        val wheelchairCount = minOf(count, wheelchairSeats.size)
        result.addAll(wheelchairSeats.take(wheelchairCount).map { it.seat })
        
        val remaining = count - wheelchairCount
        if (remaining > 0 && reserveCompanionSeats) {
            val companions = findCompanionSeats(available, wheelchairSeats.take(wheelchairCount), remaining)
            result.addAll(companions)
        }
        
        return if (result.size >= count) result.take(count) else emptyList()
    }
    
    private fun selectNonAccessibleSeats(
        available: List<ShowSeat>,
        count: Int,
        preferences: SeatPreferences
    ): List<Seat> {
        val nonWheelchair = available.filter { it.seat.type != SeatType.WHEELCHAIR }
        
        if (nonWheelchair.size < count) {
            return emptyList()
        }
        
        return BestAvailableStrategy().selectSeats(nonWheelchair, count, preferences)
    }
    
    private fun findCompanionSeats(
        available: List<ShowSeat>,
        wheelchairSeats: List<ShowSeat>,
        count: Int
    ): List<Seat> {
        val companions = mutableListOf<Seat>()
        val usedIds = wheelchairSeats.map { it.seat.id }.toMutableSet()
        
        for (wheelchairSeat in wheelchairSeats) {
            if (companions.size >= count) break
            
            val adjacentSeats = available.filter { showSeat ->
                showSeat.seat.id !in usedIds &&
                showSeat.seat.row == wheelchairSeat.seat.row &&
                abs(showSeat.seat.number - wheelchairSeat.seat.number) == 1
            }
            
            adjacentSeats.take(minOf(companionSeatsPerWheelchair, count - companions.size))
                .forEach { 
                    companions.add(it.seat)
                    usedIds.add(it.seat.id)
                }
        }
        
        return companions
    }
}

/**
 * Selects seats while maintaining social distancing between groups.
 * Useful during pandemic situations or for VIP experiences.
 */
class SocialDistancingStrategy(
    private val minSpacing: Int = 2,
    private val fallbackStrategy: SeatSelectionStrategy = BestAvailableStrategy()
) : SeatSelectionStrategy {
    
    override fun selectSeats(
        availableSeats: List<ShowSeat>,
        count: Int,
        preferences: SeatPreferences
    ): List<Seat> {
        val available = availableSeats.filter { it.isAvailable() }
        val seatsByRow = available.groupBy { it.seat.row }
        
        for ((_, rowSeats) in seatsByRow.entries.sortedByDescending { it.value.size }) {
            val validGroup = findDistancedGroup(rowSeats, count, availableSeats)
            if (validGroup != null) {
                return validGroup.map { it.seat }
            }
        }
        
        return fallbackStrategy.selectSeats(availableSeats, count, preferences)
    }
    
    private fun findDistancedGroup(
        rowSeats: List<ShowSeat>,
        count: Int,
        allSeats: List<ShowSeat>
    ): List<ShowSeat>? {
        val sorted = rowSeats.sortedBy { it.seat.number }
        
        for (start in 0..sorted.size - count) {
            val group = sorted.subList(start, start + count)
            if (isProperlySpaced(group, allSeats)) {
                return group
            }
        }
        
        return null
    }
    
    private fun isProperlySpaced(group: List<ShowSeat>, allSeats: List<ShowSeat>): Boolean {
        val row = group.first().seat.row
        val minNum = group.minOf { it.seat.number }
        val maxNum = group.maxOf { it.seat.number }
        
        val bookedInRow = allSeats
            .filter { it.seat.row == row && !it.isAvailable() }
            .map { it.seat.number }
        
        for (bookedNum in bookedInRow) {
            if (bookedNum < minNum && minNum - bookedNum < minSpacing + 1) return false
            if (bookedNum > maxNum && bookedNum - maxNum < minSpacing + 1) return false
        }
        
        return true
    }
}

/**
 * Composite strategy that tries multiple strategies in order.
 * Falls back to next strategy if previous one fails.
 */
class CompositeSelectionStrategy(
    private val strategies: List<SeatSelectionStrategy>
) : SeatSelectionStrategy {
    
    override fun selectSeats(
        availableSeats: List<ShowSeat>,
        count: Int,
        preferences: SeatPreferences
    ): List<Seat> {
        for (strategy in strategies) {
            val result = strategy.selectSeats(availableSeats, count, preferences)
            if (result.isNotEmpty()) {
                return result
            }
        }
        return emptyList()
    }
}

/**
 * Strategy selector that chooses the appropriate strategy based on context.
 * Extensibility point for adding new strategy selection logic.
 */
class StrategySelector {
    
    fun selectStrategy(preferences: SeatPreferences, showContext: ShowContext): SeatSelectionStrategy {
        return when {
            preferences.accessibilityRequired -> AccessibleFirstStrategy()
            
            showContext.socialDistancingEnabled -> 
                SocialDistancingStrategy(minSpacing = showContext.minSpacing)
            
            preferences.preferredRows.isNotEmpty() && preferences.preferredType != null ->
                ManualSelectionStrategy()
            
            else -> BestAvailableStrategy(
                preferMiddleRows = true,
                preferCenter = true
            )
        }
    }
}

/** Context about the show for strategy selection */
data class ShowContext(
    val showId: String,
    val isPremiere: Boolean = false,
    val socialDistancingEnabled: Boolean = false,
    val minSpacing: Int = 2
)

/**
 * Booking service using strategy pattern for seat selection
 */
class StrategyBasedBookingService(
    private val defaultStrategy: SeatSelectionStrategy = BestAvailableStrategy()
) {
    private val showSeats = mutableMapOf<String, MutableList<ShowSeat>>()
    private var currentStrategy: SeatSelectionStrategy = defaultStrategy
    
    fun setStrategy(strategy: SeatSelectionStrategy) {
        currentStrategy = strategy
    }
    
    fun getStrategy(): SeatSelectionStrategy = currentStrategy
    
    fun registerShow(show: Show) {
        showSeats[show.id] = show.screen.generateSeats()
            .map { ShowSeat(it) }
            .toMutableList()
    }
    
    fun getAvailableSeats(showId: String): List<ShowSeat> {
        return showSeats[showId]?.filter { it.isAvailable() } ?: emptyList()
    }
    
    /**
     * Auto-select seats using current strategy
     */
    fun autoSelectSeats(
        showId: String,
        count: Int,
        preferences: SeatPreferences = SeatPreferences()
    ): List<Seat> {
        val available = getAvailableSeats(showId)
        return currentStrategy.selectSeats(available, count, preferences)
    }
    
    /**
     * Auto-select with context-aware strategy
     */
    fun autoSelectWithContext(
        showId: String,
        count: Int,
        preferences: SeatPreferences,
        context: ShowContext
    ): List<Seat> {
        val strategy = StrategySelector().selectStrategy(preferences, context)
        val available = getAvailableSeats(showId)
        return strategy.selectSeats(available, count, preferences)
    }
}
