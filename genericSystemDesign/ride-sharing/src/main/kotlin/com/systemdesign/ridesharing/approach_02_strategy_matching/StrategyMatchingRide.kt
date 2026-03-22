package com.systemdesign.ridesharing.approach_02_strategy_matching

import com.systemdesign.ridesharing.common.*

/**
 * Approach 2: Strategy Pattern for Driver Matching
 * 
 * Different matching algorithms can be swapped at runtime based on conditions.
 * Examples: nearest driver, highest rated, surge-aware, pool-optimized.
 * 
 * Pattern: Strategy Pattern
 * 
 * Trade-offs:
 * + Different matching for different ride types (premium vs pool)
 * + Easy to A/B test matching algorithms
 * + Algorithms testable in isolation
 * + Runtime strategy switching based on demand
 * - Matching strategy needs visibility into all drivers
 * - Complex strategies may have similar performance characteristics
 * 
 * When to use:
 * - When matching policy varies by ride type/customer tier
 * - When A/B testing different matching approaches
 * - When optimizing for different metrics (ETA, driver earnings, rider satisfaction)
 * 
 * Extensibility:
 * - New matching strategy: Implement DriverMatchingStrategy interface
 * - Composite strategies: Use CompositeMatchingStrategy to combine multiple
 * - ML-based matching: Implement strategy that calls ML service
 */

/**
 * Finds the nearest available driver
 * 
 * Best for: Quick pickups in high-supply areas
 * Drawback: May assign drivers with lower ratings
 */
class NearestDriverStrategy(
    private val maxDistanceKm: Double = 10.0
) : DriverMatchingStrategy {
    
    override fun findBestMatch(
        request: RideRequest,
        availableDrivers: List<Driver>,
        surgeZones: List<SurgeZone>
    ): MatchResult? {
        return availableDrivers
            .filter { driver ->
                driver.canAccept(request.rideType) &&
                driver.location.isWithinRadius(request.pickup, maxDistanceKm)
            }
            .map { driver -> MatchResult.fromDriverAndPickup(driver, request.pickup) }
            .minByOrNull { it.distance }
    }
}

/**
 * Finds the highest-rated driver within acceptable distance
 * 
 * Best for: Premium rides, high-value customers
 * Drawback: May increase wait time
 */
class HighestRatedStrategy(
    private val maxDistanceKm: Double = 15.0,
    private val minRating: Double = 4.0
) : DriverMatchingStrategy {
    
    override fun findBestMatch(
        request: RideRequest,
        availableDrivers: List<Driver>,
        surgeZones: List<SurgeZone>
    ): MatchResult? {
        return availableDrivers
            .filter { driver ->
                driver.canAccept(request.rideType) &&
                driver.rating >= minRating &&
                driver.location.isWithinRadius(request.pickup, maxDistanceKm)
            }
            .map { driver -> MatchResult.fromDriverAndPickup(driver, request.pickup) }
            .maxByOrNull { it.driver.rating }
    }
}

/**
 * Considers surge pricing zones to optimize driver supply
 * 
 * Prioritizes drivers moving INTO surge zones to balance supply
 * Best for: Rebalancing driver supply during high demand
 * Drawback: May slightly increase ETA
 */
class SurgeAwareStrategy(
    private val maxDistanceKm: Double = 12.0,
    private val surgeBonus: Double = 20.0
) : DriverMatchingStrategy {
    
    override fun findBestMatch(
        request: RideRequest,
        availableDrivers: List<Driver>,
        surgeZones: List<SurgeZone>
    ): MatchResult? {
        val activeSurgeZone = surgeZones
            .filter { it.isActive() && it.contains(request.pickup) }
            .maxByOrNull { it.multiplier }
        
        return availableDrivers
            .filter { driver ->
                driver.canAccept(request.rideType) &&
                driver.location.isWithinRadius(request.pickup, maxDistanceKm)
            }
            .map { driver ->
                val match = MatchResult.fromDriverAndPickup(driver, request.pickup)
                val adjustedScore = calculateSurgeAdjustedScore(
                    match, 
                    driver, 
                    activeSurgeZone
                )
                match.copy(score = adjustedScore)
            }
            .maxByOrNull { it.score }
    }
    
    private fun calculateSurgeAdjustedScore(
        match: MatchResult,
        driver: Driver,
        surgeZone: SurgeZone?
    ): Double {
        var score = match.score
        
        if (surgeZone != null) {
            val driverInSurge = surgeZone.contains(driver.location)
            
            if (!driverInSurge) {
                // Bonus for drivers moving INTO surge zone
                score += surgeBonus * surgeZone.multiplier
            }
            
            // Higher acceptance rate drivers get priority during surge
            score += driver.acceptanceRate * 15
        }
        
        return score
    }
}

/**
 * Optimizes for pool rides - finds drivers with existing passengers going same direction
 * 
 * Best for: Pool/shared rides
 * Drawback: More complex routing, potential delays
 */
class PoolOptimizedStrategy(
    private val maxDetourMinutes: Int = 10,
    private val maxDistanceKm: Double = 8.0
) : DriverMatchingStrategy {
    
    private val activePoolRides = mutableMapOf<String, PoolRideInfo>()
    
    data class PoolRideInfo(
        val driverId: String,
        val currentPassengers: Int,
        val maxPassengers: Int,
        val currentRoute: Route,
        val dropoffLocations: List<Location>
    )
    
    fun registerPoolRide(driverId: String, info: PoolRideInfo) {
        activePoolRides[driverId] = info
    }
    
    fun unregisterPoolRide(driverId: String) {
        activePoolRides.remove(driverId)
    }
    
    override fun findBestMatch(
        request: RideRequest,
        availableDrivers: List<Driver>,
        surgeZones: List<SurgeZone>
    ): MatchResult? {
        if (request.rideType != RideType.POOL) {
            return NearestDriverStrategy(maxDistanceKm).findBestMatch(
                request, availableDrivers, surgeZones
            )
        }
        
        // First, check for existing pool rides
        val poolMatch = findExistingPoolMatch(request)
        if (poolMatch != null) return poolMatch
        
        // Fall back to finding new driver
        return availableDrivers
            .filter { driver ->
                driver.canAccept(RideType.POOL) &&
                driver.location.isWithinRadius(request.pickup, maxDistanceKm)
            }
            .map { MatchResult.fromDriverAndPickup(it, request.pickup) }
            .minByOrNull { it.etaMinutes }
    }
    
    private fun findExistingPoolMatch(request: RideRequest): MatchResult? {
        return activePoolRides.values
            .filter { pool ->
                pool.currentPassengers < pool.maxPassengers &&
                isRouteCompatible(pool, request)
            }
            .mapNotNull { pool ->
                // Find the driver in available drivers (they might be "available" for more passengers)
                val detour = calculateDetour(pool, request)
                if (detour <= maxDetourMinutes) {
                    // Create match with adjusted ETA
                    MatchResult(
                        driver = Driver(
                            id = pool.driverId,
                            name = "",
                            location = pool.currentRoute.waypoints.last(),
                            rating = 0.0,
                            isAvailable = true,
                            vehicleType = VehicleType.SEDAN
                        ),
                        etaMinutes = detour,
                        distance = 0.0,
                        score = 100.0 - detour // Prefer shorter detours
                    )
                } else null
            }
            .maxByOrNull { it.score }
    }
    
    private fun isRouteCompatible(pool: PoolRideInfo, request: RideRequest): Boolean {
        // Simplified: check if dropoff is roughly in same direction
        val poolDirection = calculateDirection(
            pool.currentRoute.waypoints.first(),
            pool.currentRoute.waypoints.last()
        )
        val requestDirection = calculateDirection(request.pickup, request.dropoff)
        
        return kotlin.math.abs(poolDirection - requestDirection) < 45 // Within 45 degrees
    }
    
    private fun calculateDetour(pool: PoolRideInfo, request: RideRequest): Int {
        // Simplified detour calculation
        val pickupDetour = pool.currentRoute.waypoints.last().distanceTo(request.pickup)
        val additionalTime = (pickupDetour / 30 * 60).toInt()
        return additionalTime
    }
    
    private fun calculateDirection(from: Location, to: Location): Double {
        return kotlin.math.atan2(to.lng - from.lng, to.lat - from.lat) * 180 / Math.PI
    }
}

/**
 * Balanced strategy considering multiple factors with configurable weights
 * 
 * Best for: General purpose matching with tuneable priorities
 */
class BalancedMatchingStrategy(
    private val distanceWeight: Double = 0.4,
    private val ratingWeight: Double = 0.3,
    private val acceptanceWeight: Double = 0.2,
    private val experienceWeight: Double = 0.1,
    private val maxDistanceKm: Double = 15.0
) : DriverMatchingStrategy {
    
    init {
        require(distanceWeight + ratingWeight + acceptanceWeight + experienceWeight == 1.0) {
            "Weights must sum to 1.0"
        }
    }
    
    override fun findBestMatch(
        request: RideRequest,
        availableDrivers: List<Driver>,
        surgeZones: List<SurgeZone>
    ): MatchResult? {
        val candidates = availableDrivers
            .filter { driver ->
                driver.canAccept(request.rideType) &&
                driver.location.isWithinRadius(request.pickup, maxDistanceKm)
            }
        
        if (candidates.isEmpty()) return null
        
        val maxDistance = candidates.maxOf { it.location.distanceTo(request.pickup) }
        val maxRides = candidates.maxOf { it.totalRides }.coerceAtLeast(1)
        
        return candidates
            .map { driver ->
                val distance = driver.location.distanceTo(request.pickup)
                val match = MatchResult.fromDriverAndPickup(driver, request.pickup)
                
                val normalizedDistance = 1.0 - (distance / maxDistance)
                val normalizedRating = driver.rating / 5.0
                val normalizedAcceptance = driver.acceptanceRate
                val normalizedExperience = driver.totalRides.toDouble() / maxRides
                
                val score = (normalizedDistance * distanceWeight +
                            normalizedRating * ratingWeight +
                            normalizedAcceptance * acceptanceWeight +
                            normalizedExperience * experienceWeight) * 100
                
                match.copy(score = score)
            }
            .maxByOrNull { it.score }
    }
}

/**
 * Composite strategy that tries multiple strategies in order
 */
class CompositeMatchingStrategy(
    private val strategies: List<DriverMatchingStrategy>
) : DriverMatchingStrategy {
    
    override fun findBestMatch(
        request: RideRequest,
        availableDrivers: List<Driver>,
        surgeZones: List<SurgeZone>
    ): MatchResult? {
        for (strategy in strategies) {
            val match = strategy.findBestMatch(request, availableDrivers, surgeZones)
            if (match != null) return match
        }
        return null
    }
}

/**
 * Strategy selector based on ride type and conditions
 */
class AdaptiveMatchingStrategy(
    private val nearestStrategy: NearestDriverStrategy = NearestDriverStrategy(),
    private val ratedStrategy: HighestRatedStrategy = HighestRatedStrategy(),
    private val surgeStrategy: SurgeAwareStrategy = SurgeAwareStrategy(),
    private val poolStrategy: PoolOptimizedStrategy = PoolOptimizedStrategy()
) : DriverMatchingStrategy {
    
    override fun findBestMatch(
        request: RideRequest,
        availableDrivers: List<Driver>,
        surgeZones: List<SurgeZone>
    ): MatchResult? {
        val strategy = selectStrategy(request, surgeZones)
        return strategy.findBestMatch(request, availableDrivers, surgeZones)
    }
    
    private fun selectStrategy(
        request: RideRequest,
        surgeZones: List<SurgeZone>
    ): DriverMatchingStrategy {
        // Check if in surge zone
        val inSurge = surgeZones.any { it.isActive() && it.contains(request.pickup) }
        
        return when {
            request.rideType == RideType.POOL -> poolStrategy
            request.rideType == RideType.PREMIUM -> ratedStrategy
            inSurge -> surgeStrategy
            else -> nearestStrategy
        }
    }
}

/**
 * Ride matching service that manages driver pool and matching
 */
class RideMatchingService(
    private var strategy: DriverMatchingStrategy = NearestDriverStrategy()
) {
    private val availableDrivers = mutableMapOf<String, Driver>()
    private val surgeZones = mutableListOf<SurgeZone>()
    
    fun setStrategy(newStrategy: DriverMatchingStrategy) {
        strategy = newStrategy
    }
    
    fun registerDriver(driver: Driver) {
        availableDrivers[driver.id] = driver
    }
    
    fun unregisterDriver(driverId: String) {
        availableDrivers.remove(driverId)
    }
    
    fun updateDriverLocation(driverId: String, location: Location) {
        availableDrivers[driverId]?.let { driver ->
            availableDrivers[driverId] = driver.withLocation(location)
        }
    }
    
    fun setDriverAvailability(driverId: String, available: Boolean) {
        availableDrivers[driverId]?.let { driver ->
            availableDrivers[driverId] = driver.withAvailability(available)
        }
    }
    
    fun addSurgeZone(zone: SurgeZone) {
        surgeZones.add(zone)
    }
    
    fun removeSurgeZone(zoneId: String) {
        surgeZones.removeAll { it.zoneId == zoneId }
    }
    
    fun clearExpiredSurgeZones() {
        surgeZones.removeAll { !it.isActive() }
    }
    
    fun findMatch(request: RideRequest): MatchResult? {
        clearExpiredSurgeZones()
        
        val drivers = availableDrivers.values
            .filter { it.isAvailable }
            .toList()
        
        return strategy.findBestMatch(request, drivers, surgeZones)
    }
    
    fun getDriverStats(location: Location, radiusKm: Double = 5.0): DriverStats {
        val nearbyDrivers = availableDrivers.values
            .filter { it.location.isWithinRadius(location, radiusKm) }
        
        val available = nearbyDrivers.count { it.isAvailable }
        
        val surgeMultiplier = surgeZones
            .filter { it.isActive() && it.contains(location) }
            .maxOfOrNull { it.multiplier } ?: 1.0
        
        val avgEta = if (available > 0) {
            nearbyDrivers
                .filter { it.isAvailable }
                .map { (it.location.distanceTo(location) / 30 * 60).toInt() }
                .average()
                .toInt()
        } else {
            -1
        }
        
        return DriverStats(
            availableDrivers = available,
            totalDrivers = nearbyDrivers.size,
            averageEta = avgEta,
            surgeMultiplier = surgeMultiplier
        )
    }
    
    fun getAvailableDriverCount(): Int = availableDrivers.values.count { it.isAvailable }
    
    fun getSurgeMultiplier(location: Location): Double {
        return surgeZones
            .filter { it.isActive() && it.contains(location) }
            .maxOfOrNull { it.multiplier } ?: 1.0
    }
}
