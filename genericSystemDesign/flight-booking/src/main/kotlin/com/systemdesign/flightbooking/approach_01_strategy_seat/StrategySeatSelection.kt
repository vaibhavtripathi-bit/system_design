package com.systemdesign.flightbooking.approach_01_strategy_seat

import com.systemdesign.flightbooking.common.*
import kotlin.math.abs

/**
 * Approach 1: Strategy Pattern for Seat Selection
 * 
 * Different algorithms for selecting seats based on passenger preferences.
 * The strategy pattern allows swapping selection logic at runtime.
 * 
 * Pattern: Strategy
 * 
 * Trade-offs:
 * + Easy to add new selection algorithms without modifying existing code
 * + Strategies can be combined or chained
 * + Testable in isolation
 * - More classes to maintain
 * - Strategy selection logic can become complex
 * 
 * When to use:
 * - When you have multiple algorithms for the same task
 * - When algorithms need to be interchangeable at runtime
 * - When you want to isolate algorithm-specific code
 * 
 * Extensibility:
 * - New strategy: Implement SeatSelectionStrategy interface
 * - Composite strategies: Use CompositeStrategy to combine multiple
 */

/** Strategy interface for seat selection */
interface SeatSelectionStrategy {
    val name: String
    
    fun selectSeat(
        preferences: SeatPreferences,
        availableSeats: List<Seat>
    ): SeatSelectionResult
    
    fun selectMultipleSeats(
        preferences: SeatPreferences,
        availableSeats: List<Seat>,
        count: Int
    ): MultiSeatSelectionResult
    
    fun scoreSeat(seat: Seat, preferences: SeatPreferences): Double
}

/** Result of single seat selection */
sealed class SeatSelectionResult {
    data class Selected(val seat: Seat, val score: Double) : SeatSelectionResult()
    data class NoMatch(val reason: String, val alternatives: List<Seat> = emptyList()) : SeatSelectionResult()
}

/** Result of multiple seat selection */
sealed class MultiSeatSelectionResult {
    data class Selected(val seats: List<Seat>, val totalScore: Double) : MultiSeatSelectionResult()
    data class PartialMatch(
        val selectedSeats: List<Seat>,
        val missingCount: Int,
        val reason: String
    ) : MultiSeatSelectionResult()
    data class NoMatch(val reason: String) : MultiSeatSelectionResult()
}

/** Base class with common scoring logic */
abstract class BaseSeatSelectionStrategy : SeatSelectionStrategy {
    
    override fun selectSeat(
        preferences: SeatPreferences,
        availableSeats: List<Seat>
    ): SeatSelectionResult {
        if (availableSeats.isEmpty()) {
            return SeatSelectionResult.NoMatch("No seats available")
        }
        
        val filteredSeats = filterByClass(availableSeats, preferences.preferredClass)
        if (filteredSeats.isEmpty()) {
            return SeatSelectionResult.NoMatch(
                "No ${preferences.preferredClass} class seats available",
                availableSeats.take(3)
            )
        }
        
        val scoredSeats = filteredSeats.map { it to scoreSeat(it, preferences) }
        val bestSeat = scoredSeats.maxByOrNull { it.second }
        
        return if (bestSeat != null) {
            SeatSelectionResult.Selected(bestSeat.first, bestSeat.second)
        } else {
            SeatSelectionResult.NoMatch("Could not find suitable seat")
        }
    }
    
    override fun selectMultipleSeats(
        preferences: SeatPreferences,
        availableSeats: List<Seat>,
        count: Int
    ): MultiSeatSelectionResult {
        if (availableSeats.size < count) {
            return MultiSeatSelectionResult.NoMatch(
                "Only ${availableSeats.size} seats available, need $count"
            )
        }
        
        val filteredSeats = filterByClass(availableSeats, preferences.preferredClass)
        if (filteredSeats.size < count) {
            return MultiSeatSelectionResult.PartialMatch(
                filteredSeats,
                count - filteredSeats.size,
                "Not enough ${preferences.preferredClass} class seats"
            )
        }
        
        val scoredSeats = filteredSeats
            .map { it to scoreSeat(it, preferences) }
            .sortedByDescending { it.second }
        
        val selectedSeats = scoredSeats.take(count).map { it.first }
        val totalScore = scoredSeats.take(count).sumOf { it.second }
        
        return MultiSeatSelectionResult.Selected(selectedSeats, totalScore)
    }
    
    protected fun filterByClass(seats: List<Seat>, seatClass: SeatClass): List<Seat> =
        seats.filter { it.seatClass == seatClass }
    
    protected fun baseScore(seat: Seat, preferences: SeatPreferences): Double {
        var score = 0.0
        
        if (preferences.preferExtraLegroom && seat.hasExtraLegroom) score += 20.0
        if (preferences.preferEmergencyExit && seat.isEmergencyExit) score += 15.0
        
        preferences.nearRow?.let { targetRow ->
            val distance = abs(seat.row - targetRow)
            score += maxOf(0.0, 10.0 - distance)
        }
        
        return score
    }
}

/** Strategy: Prefer window seats */
class WindowPreferenceStrategy : BaseSeatSelectionStrategy() {
    override val name = "Window Preference"
    
    override fun scoreSeat(seat: Seat, preferences: SeatPreferences): Double {
        var score = baseScore(seat, preferences)
        
        when (seat.type) {
            SeatType.WINDOW -> score += 100.0
            SeatType.AISLE -> score += 30.0
            SeatType.MIDDLE -> score += 10.0
        }
        
        return score
    }
}

/** Strategy: Prefer aisle seats */
class AislePreferenceStrategy : BaseSeatSelectionStrategy() {
    override val name = "Aisle Preference"
    
    override fun scoreSeat(seat: Seat, preferences: SeatPreferences): Double {
        var score = baseScore(seat, preferences)
        
        when (seat.type) {
            SeatType.AISLE -> score += 100.0
            SeatType.WINDOW -> score += 30.0
            SeatType.MIDDLE -> score += 10.0
        }
        
        return score
    }
}

/** Strategy: Keep family/group together in adjacent seats */
class FamilyGroupingStrategy : BaseSeatSelectionStrategy() {
    override val name = "Family Grouping"
    
    override fun scoreSeat(seat: Seat, preferences: SeatPreferences): Double {
        return baseScore(seat, preferences) + 50.0
    }
    
    override fun selectMultipleSeats(
        preferences: SeatPreferences,
        availableSeats: List<Seat>,
        count: Int
    ): MultiSeatSelectionResult {
        if (availableSeats.size < count) {
            return MultiSeatSelectionResult.NoMatch(
                "Only ${availableSeats.size} seats available, need $count"
            )
        }
        
        val filteredSeats = filterByClass(availableSeats, preferences.preferredClass)
        if (filteredSeats.size < count) {
            return MultiSeatSelectionResult.PartialMatch(
                filteredSeats,
                count - filteredSeats.size,
                "Not enough ${preferences.preferredClass} class seats"
            )
        }
        
        val adjacentGroups = findAdjacentSeatGroups(filteredSeats, count)
        
        if (adjacentGroups.isNotEmpty()) {
            val bestGroup = adjacentGroups
                .maxByOrNull { group -> group.sumOf { scoreSeat(it, preferences) } }!!
            val totalScore = bestGroup.sumOf { scoreSeat(it, preferences) } + 50.0 * count
            return MultiSeatSelectionResult.Selected(bestGroup, totalScore)
        }
        
        if (!preferences.keepTogether) {
            return super.selectMultipleSeats(preferences, availableSeats, count)
        }
        
        val sameRowGroups = findSameRowSeats(filteredSeats, count)
        if (sameRowGroups.isNotEmpty()) {
            val bestGroup = sameRowGroups
                .maxByOrNull { group -> group.sumOf { scoreSeat(it, preferences) } }!!
            val totalScore = bestGroup.sumOf { scoreSeat(it, preferences) } + 25.0 * count
            return MultiSeatSelectionResult.Selected(bestGroup, totalScore)
        }
        
        return MultiSeatSelectionResult.PartialMatch(
            filteredSeats.take(count),
            0,
            "Could not find adjacent seats, assigned best available"
        )
    }
    
    private fun findAdjacentSeatGroups(seats: List<Seat>, size: Int): List<List<Seat>> {
        val groups = mutableListOf<List<Seat>>()
        val seatsByRow = seats.groupBy { it.row }
        
        for ((_, rowSeats) in seatsByRow) {
            val sortedSeats = rowSeats.sortedBy { it.column }
            if (sortedSeats.size >= size) {
                for (i in 0..sortedSeats.size - size) {
                    val group = sortedSeats.subList(i, i + size)
                    if (areAdjacent(group)) {
                        groups.add(group)
                    }
                }
            }
        }
        
        return groups
    }
    
    private fun findSameRowSeats(seats: List<Seat>, size: Int): List<List<Seat>> {
        return seats.groupBy { it.row }
            .filter { it.value.size >= size }
            .map { it.value.take(size) }
    }
    
    private fun areAdjacent(seats: List<Seat>): Boolean {
        if (seats.size <= 1) return true
        val sorted = seats.sortedBy { it.column }
        for (i in 0 until sorted.size - 1) {
            if (sorted[i + 1].column - sorted[i].column != 1) {
                return false
            }
        }
        return true
    }
}

/** Strategy: Prioritize extra legroom seats */
class ExtraLegroomStrategy : BaseSeatSelectionStrategy() {
    override val name = "Extra Legroom"
    
    override fun scoreSeat(seat: Seat, preferences: SeatPreferences): Double {
        var score = baseScore(seat, preferences)
        
        if (seat.hasExtraLegroom) score += 100.0
        if (seat.isEmergencyExit) score += 80.0
        
        preferences.preferredType?.let { type ->
            if (seat.type == type) score += 30.0
        }
        
        return score
    }
}

/** Strategy: Cheapest available seats */
class BudgetStrategy : BaseSeatSelectionStrategy() {
    override val name = "Budget"
    
    override fun scoreSeat(seat: Seat, preferences: SeatPreferences): Double {
        val maxPrice = 500.0
        val priceScore = maxOf(0.0, maxPrice - seat.price)
        return priceScore + baseScore(seat, preferences) * 0.1
    }
}

/** Composite strategy combining multiple strategies with weights */
class CompositeStrategy(
    private val strategies: List<Pair<SeatSelectionStrategy, Double>>
) : BaseSeatSelectionStrategy() {
    override val name = "Composite"
    
    override fun scoreSeat(seat: Seat, preferences: SeatPreferences): Double {
        return strategies.sumOf { (strategy, weight) ->
            strategy.scoreSeat(seat, preferences) * weight
        }
    }
}

/** Seat selector using strategy pattern */
class SeatSelector(
    private var strategy: SeatSelectionStrategy = WindowPreferenceStrategy()
) {
    fun setStrategy(strategy: SeatSelectionStrategy) {
        this.strategy = strategy
    }
    
    fun getStrategy(): SeatSelectionStrategy = strategy
    
    fun selectSeat(
        flight: Flight,
        preferences: SeatPreferences,
        bookedSeats: Set<String> = emptySet()
    ): SeatSelectionResult {
        val availableSeats = flight.getAvailableSeats(bookedSeats)
        return strategy.selectSeat(preferences, availableSeats)
    }
    
    fun selectSeatsForGroup(
        flight: Flight,
        preferences: SeatPreferences,
        passengerCount: Int,
        bookedSeats: Set<String> = emptySet()
    ): MultiSeatSelectionResult {
        val availableSeats = flight.getAvailableSeats(bookedSeats)
        return strategy.selectMultipleSeats(preferences, availableSeats, passengerCount)
    }
    
    fun recommendStrategy(preferences: SeatPreferences): SeatSelectionStrategy {
        return when {
            preferences.passengerCount > 1 && preferences.keepTogether -> FamilyGroupingStrategy()
            preferences.preferExtraLegroom -> ExtraLegroomStrategy()
            preferences.preferredType == SeatType.WINDOW -> WindowPreferenceStrategy()
            preferences.preferredType == SeatType.AISLE -> AislePreferenceStrategy()
            else -> WindowPreferenceStrategy()
        }
    }
    
    fun selectWithRecommendedStrategy(
        flight: Flight,
        preferences: SeatPreferences,
        bookedSeats: Set<String> = emptySet()
    ): SeatSelectionResult {
        val recommendedStrategy = recommendStrategy(preferences)
        setStrategy(recommendedStrategy)
        return selectSeat(flight, preferences, bookedSeats)
    }
}

/** Strategy registry for dynamic strategy lookup */
class SeatSelectionStrategyRegistry {
    private val strategies = mutableMapOf<String, SeatSelectionStrategy>()
    
    init {
        register("window", WindowPreferenceStrategy())
        register("aisle", AislePreferenceStrategy())
        register("family", FamilyGroupingStrategy())
        register("legroom", ExtraLegroomStrategy())
        register("budget", BudgetStrategy())
    }
    
    fun register(name: String, strategy: SeatSelectionStrategy) {
        strategies[name.lowercase()] = strategy
    }
    
    fun get(name: String): SeatSelectionStrategy? = strategies[name.lowercase()]
    
    fun getOrDefault(name: String): SeatSelectionStrategy =
        strategies[name.lowercase()] ?: WindowPreferenceStrategy()
    
    fun listStrategies(): List<String> = strategies.keys.toList()
}
