package com.systemdesign.ridesharing

import com.systemdesign.ridesharing.common.*
import com.systemdesign.ridesharing.approach_01_state_machine.*
import com.systemdesign.ridesharing.approach_02_strategy_matching.*
import com.systemdesign.ridesharing.approach_03_decorator_pricing.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import java.time.LocalDateTime

class RideSharingTest {
    
    private val testPickup = Location(37.7749, -122.4194) // San Francisco
    private val testDropoff = Location(37.3382, -121.8863) // San Jose (~50km)
    private val nearbyLocation = Location(37.7850, -122.4094) // ~1km from pickup
    
    private fun createTestRider(id: String = "rider-1"): Rider {
        return Rider(
            id = id,
            name = "Test Rider",
            rating = 4.5,
            totalRides = 10,
            paymentMethodId = "pm_123"
        )
    }
    
    private fun createTestDriver(
        id: String = "driver-1",
        location: Location = nearbyLocation,
        rating: Double = 4.8,
        isAvailable: Boolean = true,
        vehicleType: VehicleType = VehicleType.SEDAN
    ): Driver {
        return Driver(
            id = id,
            name = "Test Driver",
            location = location,
            rating = rating,
            isAvailable = isAvailable,
            vehicleType = vehicleType,
            totalRides = 100,
            acceptanceRate = 0.95
        )
    }
    
    private fun createTestRequest(
        rideType: RideType = RideType.STANDARD,
        requestTime: LocalDateTime = LocalDateTime.now()
    ): RideRequest {
        return RideRequest(
            rider = createTestRider(),
            pickup = testPickup,
            dropoff = testDropoff,
            rideType = rideType,
            requestTime = requestTime
        )
    }
    
    @Nested
    inner class StateMachineTest {
        
        private lateinit var stateMachine: RideStateMachine
        private lateinit var matchingService: RideMatchingService
        
        @BeforeEach
        fun setup() {
            matchingService = RideMatchingService(NearestDriverStrategy())
            stateMachine = RideStateMachineBuilder()
                .withMatchingStrategy(NearestDriverStrategy())
                .withPricingStrategy(BasePricingStrategy())
                .build()
        }
        
        @Test
        fun `ride starts in REQUESTED state`() {
            val request = createTestRequest()
            val ride = stateMachine.requestRide(request)
            
            assertEquals(RideState.REQUESTED, ride.state)
            assertNotNull(ride.id)
            assertEquals(request, ride.request)
        }
        
        @Test
        fun `ride transitions from REQUESTED to MATCHING`() {
            val request = createTestRequest()
            val ride = stateMachine.requestRide(request)
            
            val drivers = listOf(createTestDriver())
            val updatedRide = stateMachine.startMatching(ride.id, drivers)
            
            assertNotNull(updatedRide)
            assertTrue(updatedRide!!.state in listOf(RideState.MATCHING, RideState.DRIVER_ASSIGNED))
        }
        
        @Test
        fun `ride assigns driver when match found`() {
            val request = createTestRequest()
            val ride = stateMachine.requestRide(request)
            
            val drivers = listOf(createTestDriver())
            val updatedRide = stateMachine.startMatching(ride.id, drivers)
            
            assertNotNull(updatedRide)
            assertEquals(RideState.DRIVER_ASSIGNED, updatedRide!!.state)
            assertNotNull(updatedRide.driver)
        }
        
        @Test
        fun `ride transitions through full lifecycle`() {
            val request = createTestRequest()
            var ride = stateMachine.requestRide(request)
            
            val drivers = listOf(createTestDriver())
            ride = stateMachine.startMatching(ride.id, drivers)!!
            assertEquals(RideState.DRIVER_ASSIGNED, ride.state)
            
            ride = stateMachine.driverArrived(ride.id)!!
            assertEquals(RideState.ARRIVED, ride.state)
            
            ride = stateMachine.startRide(ride.id)!!
            assertEquals(RideState.IN_PROGRESS, ride.state)
            assertNotNull(ride.startTime)
            
            ride = stateMachine.completeRide(ride.id)!!
            assertEquals(RideState.COMPLETED, ride.state)
            assertNotNull(ride.endTime)
            assertNotNull(ride.price)
        }
        
        @Test
        fun `cannot skip states`() {
            val request = createTestRequest()
            val ride = stateMachine.requestRide(request)
            
            // Try to start ride without driver assignment
            val result = stateMachine.startRide(ride.id)
            assertNull(result)
        }
        
        @Test
        fun `cannot transition from terminal state`() {
            val request = createTestRequest()
            var ride = stateMachine.requestRide(request)
            
            val drivers = listOf(createTestDriver())
            ride = stateMachine.startMatching(ride.id, drivers)!!
            ride = stateMachine.driverArrived(ride.id)!!
            ride = stateMachine.startRide(ride.id)!!
            ride = stateMachine.completeRide(ride.id)!!
            
            // Try to cancel completed ride
            val cancelResult = stateMachine.cancelRide(ride.id, CancellationReason.RIDER_CANCELLED, ride.request.rider.id)
            assertNull(cancelResult)
        }
        
        @Test
        fun `observer receives events`() {
            val events = mutableListOf<RideEvent>()
            stateMachine.addObserver(object : RideObserver {
                override fun onRideEvent(event: RideEvent) {
                    events.add(event)
                }
            })
            
            val request = createTestRequest()
            val ride = stateMachine.requestRide(request)
            
            assertTrue(events.any { it is RideEvent.RideRequested })
            assertEquals(ride.id, (events.first() as RideEvent.RideRequested).rideId)
        }
    }
    
    @Nested
    inner class CancellationTest {
        
        private lateinit var stateMachine: RideStateMachine
        
        @BeforeEach
        fun setup() {
            stateMachine = RideStateMachineBuilder()
                .withMatchingStrategy(NearestDriverStrategy())
                .withCancellationPolicy(DefaultCancellationPolicy())
                .build()
        }
        
        @Test
        fun `rider can cancel in REQUESTED state for free`() {
            val request = createTestRequest()
            val ride = stateMachine.requestRide(request)
            
            val cancelled = stateMachine.cancelRide(
                ride.id, 
                CancellationReason.RIDER_CANCELLED, 
                request.rider.id
            )
            
            assertNotNull(cancelled)
            assertEquals(RideState.CANCELLED, cancelled!!.state)
            assertEquals(0.0, cancelled.cancellation?.cancellationFee)
        }
        
        @Test
        fun `rider pays fee when cancelling after driver assigned`() {
            val request = createTestRequest()
            var ride = stateMachine.requestRide(request)
            
            val drivers = listOf(createTestDriver())
            ride = stateMachine.startMatching(ride.id, drivers)!!
            
            val cancelled = stateMachine.cancelRide(
                ride.id,
                CancellationReason.RIDER_CANCELLED,
                request.rider.id
            )
            
            assertNotNull(cancelled)
            assertEquals(RideState.CANCELLED, cancelled!!.state)
            assertTrue(cancelled.cancellation!!.cancellationFee > 0)
        }
        
        @Test
        fun `driver can cancel after assignment without fee`() {
            val request = createTestRequest()
            var ride = stateMachine.requestRide(request)
            
            val driver = createTestDriver()
            ride = stateMachine.startMatching(ride.id, listOf(driver))!!
            
            val cancelled = stateMachine.cancelRide(
                ride.id,
                CancellationReason.DRIVER_CANCELLED,
                driver.id
            )
            
            assertNotNull(cancelled)
            assertEquals(0.0, cancelled!!.cancellation?.cancellationFee)
        }
        
        @Test
        fun `premium cancellation policy has higher fees`() {
            val premiumStateMachine = RideStateMachineBuilder()
                .withMatchingStrategy(NearestDriverStrategy())
                .withCancellationPolicy(PremiumCancellationPolicy())
                .build()
            
            val request = createTestRequest(rideType = RideType.PREMIUM)
            var ride = premiumStateMachine.requestRide(request)
            
            val drivers = listOf(createTestDriver(vehicleType = VehicleType.LUXURY))
            ride = premiumStateMachine.startMatching(ride.id, drivers)!!
            
            val cancelled = premiumStateMachine.cancelRide(
                ride.id,
                CancellationReason.RIDER_CANCELLED,
                request.rider.id
            )
            
            assertNotNull(cancelled)
            assertTrue(cancelled!!.cancellation!!.cancellationFee > 5.0)
        }
    }
    
    @Nested
    inner class MatchingStrategyTest {
        
        @Test
        fun `nearest driver strategy finds closest driver`() {
            val strategy = NearestDriverStrategy()
            val request = createTestRequest()
            
            val closeDriver = createTestDriver(id = "close", location = nearbyLocation)
            val farDriver = createTestDriver(
                id = "far", 
                location = Location(37.9, -122.5) // ~15km away
            )
            
            val result = strategy.findBestMatch(request, listOf(farDriver, closeDriver))
            
            assertNotNull(result)
            assertEquals("close", result!!.driver.id)
        }
        
        @Test
        fun `highest rated strategy finds best rated driver`() {
            val strategy = HighestRatedStrategy()
            val request = createTestRequest()
            
            val avgDriver = createTestDriver(id = "avg", rating = 4.2)
            val topDriver = createTestDriver(id = "top", rating = 4.9)
            
            val result = strategy.findBestMatch(request, listOf(avgDriver, topDriver))
            
            assertNotNull(result)
            assertEquals("top", result!!.driver.id)
        }
        
        @Test
        fun `strategy filters incompatible vehicle types`() {
            val strategy = NearestDriverStrategy()
            val request = createTestRequest(rideType = RideType.PREMIUM)
            
            val sedanDriver = createTestDriver(id = "sedan", vehicleType = VehicleType.SEDAN)
            val luxuryDriver = createTestDriver(id = "luxury", vehicleType = VehicleType.LUXURY)
            
            val result = strategy.findBestMatch(request, listOf(sedanDriver, luxuryDriver))
            
            assertNotNull(result)
            assertEquals("luxury", result!!.driver.id)
        }
        
        @Test
        fun `strategy returns null when no drivers available`() {
            val strategy = NearestDriverStrategy()
            val request = createTestRequest()
            
            val unavailableDriver = createTestDriver(isAvailable = false)
            
            val result = strategy.findBestMatch(request, listOf(unavailableDriver))
            
            assertNull(result)
        }
        
        @Test
        fun `surge aware strategy considers surge zones`() {
            val strategy = SurgeAwareStrategy()
            val request = createTestRequest()
            
            val surgeZone = SurgeZone(
                zoneId = "zone-1",
                center = testPickup,
                radiusKm = 5.0,
                multiplier = 2.0,
                expiresAt = LocalDateTime.now().plusHours(1)
            )
            
            val inSurgeDriver = createTestDriver(id = "in-surge", location = nearbyLocation)
            val outSurgeDriver = createTestDriver(
                id = "out-surge",
                location = Location(37.9, -122.6) // Outside surge zone
            )
            
            val result = strategy.findBestMatch(
                request, 
                listOf(inSurgeDriver, outSurgeDriver),
                listOf(surgeZone)
            )
            
            assertNotNull(result)
        }
        
        @Test
        fun `adaptive strategy selects appropriate strategy by ride type`() {
            val strategy = AdaptiveMatchingStrategy()
            
            val standardRequest = createTestRequest(rideType = RideType.STANDARD)
            val premiumRequest = createTestRequest(rideType = RideType.PREMIUM)
            
            val avgDriver = createTestDriver(id = "avg", rating = 4.0)
            val topDriver = createTestDriver(
                id = "top", 
                rating = 4.9, 
                vehicleType = VehicleType.LUXURY
            )
            
            val standardResult = strategy.findBestMatch(
                standardRequest, 
                listOf(avgDriver, topDriver)
            )
            val premiumResult = strategy.findBestMatch(
                premiumRequest, 
                listOf(avgDriver, topDriver)
            )
            
            assertNotNull(standardResult)
            assertNotNull(premiumResult)
            assertEquals("top", premiumResult!!.driver.id)
        }
        
        @Test
        fun `matching service tracks drivers`() {
            val service = RideMatchingService()
            
            val driver = createTestDriver()
            service.registerDriver(driver)
            
            val request = createTestRequest()
            val result = service.findMatch(request)
            
            assertNotNull(result)
            
            service.setDriverAvailability(driver.id, false)
            val noResult = service.findMatch(request)
            
            assertNull(noResult)
        }
    }
    
    @Nested
    inner class PricingDecoratorTest {
        
        @Test
        fun `base pricing calculates distance and time`() {
            val strategy = BasePricingStrategy(
                baseFare = 3.0,
                perKmRate = 1.5,
                perMinuteRate = 0.2
            )
            
            val ride = createTestRide(distanceKm = 10.0, durationMinutes = 20)
            val price = strategy.calculatePrice(ride)
            
            assertEquals(3.0, price.baseFare, 0.01)
            assertEquals(15.0, price.distanceCharge, 0.01) // 10 * 1.5
            assertEquals(4.0, price.timeCharge, 0.01) // 20 * 0.2
        }
        
        @Test
        fun `surge pricing applies multiplier`() {
            val base = BasePricingStrategy(baseFare = 3.0, perKmRate = 1.5)
            val strategy = SurgePricingDecorator(base, FixedSurgeProvider(2.0))
            
            val ride = createTestRide(distanceKm = 10.0)
            val price = strategy.calculatePrice(ride)
            
            assertTrue(price.surgeFee > 0)
            assertTrue(price.total > 20) // Base would be ~20, with 2x surge
        }
        
        @Test
        fun `toll decorator adds toll fees`() {
            val base = BasePricingStrategy()
            val tollRegistry = SimpleTollRegistry(listOf(
                TollSegment(
                    "toll-1",
                    testPickup,
                    testDropoff,
                    5.0,
                    "Golden Gate Bridge"
                )
            ))
            val strategy = TollDecorator(base, tollRegistry)
            
            val ride = createTestRide()
            val price = strategy.calculatePrice(ride)
            
            assertEquals(5.0, price.tollFee, 0.01)
        }
        
        @Test
        fun `promo code decorator applies discount`() {
            val base = BasePricingStrategy(baseFare = 10.0, perKmRate = 1.0)
            val promoValidator = SimplePromoValidator()
            promoValidator.registerPromo(PromoCode(
                code = "SAVE10",
                discountType = PromoCode.DiscountType.PERCENTAGE,
                value = 10.0,
                validUntil = LocalDateTime.now().plusDays(1)
            ))
            
            val strategy = PromoCodeDecorator(base, promoValidator)
            
            val rider = createTestRider()
            val ride = createTestRide()
            
            promoValidator.applyPromo(rider.id, ride.id, "SAVE10")
            
            val price = strategy.calculatePrice(ride)
            
            assertTrue(price.promoDiscount > 0)
            assertTrue(price.total < price.subtotal)
        }
        
        @Test
        fun `stacked decorators apply in order`() {
            val strategy = PricingStrategyBuilder()
                .withBase(baseFare = 3.0, perKmRate = 1.5)
                .withSurgeMultiplier(1.5)
                .withPeakHours(surchargePercent = 0.15)
                .build()
            
            val ride = createTestRide(
                distanceKm = 10.0,
                requestTime = LocalDateTime.now().withHour(8) // Peak hour
            )
            
            val price = strategy.calculatePrice(ride)
            
            assertTrue(price.surgeFee > 0)
            assertTrue(price.total > 20)
        }
        
        @Test
        fun `loyalty discount applied for frequent riders`() {
            val loyaltyChecker = SimpleLoyaltyChecker(mapOf("rider-1" to 60))
            val strategy = PricingStrategyBuilder()
                .withBase(baseFare = 10.0, perKmRate = 1.0)
                .withLoyalty(loyaltyChecker)
                .build()
            
            val ride = createTestRide(distanceKm = 10.0)
            val price = strategy.calculatePrice(ride)
            
            // Gold tier (50+ rides) = 10% discount
            assertTrue(price.promoDiscount > 0)
        }
        
        @Test
        fun `ride type affects pricing`() {
            val strategy = BasePricingStrategy()
            
            val standardRide = createTestRide(rideType = RideType.STANDARD)
            val premiumRide = createTestRide(rideType = RideType.PREMIUM)
            val poolRide = createTestRide(rideType = RideType.POOL)
            
            val standardPrice = strategy.calculatePrice(standardRide)
            val premiumPrice = strategy.calculatePrice(premiumRide)
            val poolPrice = strategy.calculatePrice(poolRide)
            
            assertTrue(premiumPrice.total > standardPrice.total)
            assertTrue(poolPrice.total < standardPrice.total)
        }
        
        @Test
        fun `price estimator provides estimate range`() {
            val strategy = BasePricingStrategy()
            val estimator = PriceEstimator(strategy, FixedSurgeProvider(1.0))
            
            val estimate = estimator.estimatePrice(
                testPickup,
                testDropoff,
                RideType.STANDARD
            )
            
            assertTrue(estimate.minPrice < estimate.estimatedPrice)
            assertTrue(estimate.maxPrice > estimate.estimatedPrice)
            assertTrue(estimate.distanceKm > 0)
            assertTrue(estimate.estimatedDurationMinutes > 0)
        }
        
        private fun createTestRide(
            distanceKm: Double = 50.0,
            durationMinutes: Int = 60,
            rideType: RideType = RideType.STANDARD,
            requestTime: LocalDateTime = LocalDateTime.now()
        ): Ride {
            val request = RideRequest(
                rider = createTestRider(),
                pickup = testPickup,
                dropoff = testDropoff,
                rideType = rideType,
                requestTime = requestTime
            )
            return Ride(
                request = request,
                route = Route(
                    waypoints = listOf(testPickup, testDropoff),
                    distanceKm = distanceKm,
                    estimatedDurationMinutes = durationMinutes
                )
            )
        }
    }
    
    @Nested
    inner class SurgePricingTest {
        
        @Test
        fun `surge zone correctly identifies locations`() {
            val zone = SurgeZone(
                zoneId = "downtown",
                center = testPickup,
                radiusKm = 2.0,
                multiplier = 1.8,
                expiresAt = LocalDateTime.now().plusHours(1)
            )
            
            assertTrue(zone.contains(nearbyLocation))
            assertFalse(zone.contains(testDropoff)) // ~50km away
            assertTrue(zone.isActive())
        }
        
        @Test
        fun `expired surge zone is inactive`() {
            val zone = SurgeZone(
                zoneId = "downtown",
                center = testPickup,
                radiusKm = 2.0,
                multiplier = 1.8,
                expiresAt = LocalDateTime.now().minusHours(1)
            )
            
            assertFalse(zone.isActive())
        }
        
        @Test
        fun `zone surge provider returns highest multiplier`() {
            val provider = ZoneSurgeProvider(listOf(
                SurgeZone("zone1", testPickup, 5.0, 1.5, LocalDateTime.now().plusHours(1)),
                SurgeZone("zone2", testPickup, 3.0, 2.0, LocalDateTime.now().plusHours(1))
            ))
            
            val multiplier = provider.getSurgeMultiplier(testPickup)
            
            assertEquals(2.0, multiplier, 0.01)
        }
        
        @Test
        fun `matching service tracks surge zones`() {
            val service = RideMatchingService()
            
            service.addSurgeZone(SurgeZone(
                "zone1",
                testPickup,
                5.0,
                1.8,
                LocalDateTime.now().plusHours(1)
            ))
            
            val multiplier = service.getSurgeMultiplier(testPickup)
            assertEquals(1.8, multiplier, 0.01)
            
            service.removeSurgeZone("zone1")
            val noSurge = service.getSurgeMultiplier(testPickup)
            assertEquals(1.0, noSurge, 0.01)
        }
    }
    
    @Nested
    inner class LocationTest {
        
        @Test
        fun `distance calculation is accurate`() {
            // SF to SJ is approximately 60-70km by Haversine
            val distance = testPickup.distanceTo(testDropoff)
            
            assertTrue(distance > 50)
            assertTrue(distance < 80)
        }
        
        @Test
        fun `within radius check works`() {
            assertTrue(nearbyLocation.isWithinRadius(testPickup, 5.0))
            assertFalse(testDropoff.isWithinRadius(testPickup, 5.0))
        }
        
        @Test
        fun `route estimation is reasonable`() {
            val route = Route.direct(testPickup, testDropoff)
            
            assertTrue(route.distanceKm > 50)
            assertTrue(route.estimatedDurationMinutes > 40)
            assertEquals(2, route.waypoints.size)
        }
    }
    
    @Nested
    inner class ModelTest {
        
        @Test
        fun `driver can accept compatible ride types`() {
            val sedanDriver = createTestDriver(vehicleType = VehicleType.SEDAN)
            val luxuryDriver = createTestDriver(vehicleType = VehicleType.LUXURY)
            
            assertTrue(sedanDriver.canAccept(RideType.STANDARD))
            assertTrue(sedanDriver.canAccept(RideType.POOL))
            assertFalse(sedanDriver.canAccept(RideType.PREMIUM))
            assertFalse(sedanDriver.canAccept(RideType.XL))
            
            assertTrue(luxuryDriver.canAccept(RideType.PREMIUM))
            assertFalse(luxuryDriver.canAccept(RideType.STANDARD))
        }
        
        @Test
        fun `unavailable driver cannot accept any ride`() {
            val driver = createTestDriver(isAvailable = false)
            
            assertFalse(driver.canAccept(RideType.STANDARD))
        }
        
        @Test
        fun `ride state terminal check works`() {
            assertTrue(RideState.COMPLETED.isTerminal())
            assertTrue(RideState.CANCELLED.isTerminal())
            assertFalse(RideState.REQUESTED.isTerminal())
            assertFalse(RideState.IN_PROGRESS.isTerminal())
        }
        
        @Test
        fun `promo code validation works`() {
            val validPromo = PromoCode(
                code = "VALID",
                discountType = PromoCode.DiscountType.PERCENTAGE,
                value = 20.0,
                maxDiscount = 10.0,
                validUntil = LocalDateTime.now().plusDays(1)
            )
            
            val expiredPromo = validPromo.copy(
                code = "EXPIRED",
                validUntil = LocalDateTime.now().minusDays(1)
            )
            
            assertTrue(validPromo.isValid())
            assertFalse(expiredPromo.isValid())
            
            // 20% of 30 = 6, which is under max of 10
            assertEquals(6.0, validPromo.calculateDiscount(30.0), 0.01)
            
            // 20% of 100 = 20, capped at max of 10
            assertEquals(10.0, validPromo.calculateDiscount(100.0), 0.01)
        }
        
        @Test
        fun `price calculations are correct`() {
            val price = Price(
                baseFare = 3.0,
                distanceCharge = 15.0,
                timeCharge = 4.0,
                surgeFee = 5.0,
                tollFee = 2.0,
                promoDiscount = 3.0,
                bookingFee = 2.0
            )
            
            assertEquals(31.0, price.subtotal, 0.01) // 3 + 15 + 4 + 5 + 2 + 2
            assertEquals(28.0, price.total, 0.01) // 31 - 3
        }
    }
}
