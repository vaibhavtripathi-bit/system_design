package com.systemdesign.vehiclerental

import com.systemdesign.vehiclerental.common.*
import com.systemdesign.vehiclerental.approach_01_strategy_pricing.*
import com.systemdesign.vehiclerental.approach_02_state_machine.*
import com.systemdesign.vehiclerental.approach_03_factory_vehicles.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import java.time.LocalDateTime
import java.time.Month

class VehicleRentalTest {
    
    private fun createTestVehicle(
        type: VehicleType = VehicleType.COMPACT,
        dailyRate: Double = 50.0
    ): Vehicle {
        return Vehicle(
            id = "VEH-001",
            make = "Toyota",
            model = "Camry",
            year = 2023,
            type = type,
            dailyRate = dailyRate
        )
    }
    
    private fun createTestCustomer(
        tier: MembershipTier = MembershipTier.STANDARD
    ): Customer {
        return Customer(
            id = "CUST-001",
            name = "John Doe",
            license = "DL123456",
            email = "john@example.com",
            membershipTier = tier
        )
    }
    
    private fun createWeekdayPeriod(days: Long = 3): RentalPeriod {
        val start = LocalDateTime.of(2026, Month.MARCH, 23, 10, 0) // Monday
        return RentalPeriod(start, start.plusDays(days))
    }
    
    private fun createWeekendPeriod(): RentalPeriod {
        val start = LocalDateTime.of(2026, Month.MARCH, 27, 10, 0) // Friday
        return RentalPeriod(start, start.plusDays(3)) // Fri-Sun
    }
    
    private fun createLongTermPeriod(): RentalPeriod {
        val start = LocalDateTime.of(2026, Month.MARCH, 23, 10, 0)
        return RentalPeriod(start, start.plusDays(14))
    }
    
    @Nested
    inner class PricingStrategyTest {
        
        private lateinit var vehicle: Vehicle
        
        @BeforeEach
        fun setup() {
            vehicle = createTestVehicle(dailyRate = 50.0)
        }
        
        @Test
        fun `daily rate pricing calculates correct base cost`() {
            val strategy = DailyRatePricing()
            val period = createWeekdayPeriod(days = 3)
            
            val result = strategy.calculatePrice(vehicle, period, emptyList())
            
            assertEquals(150.0, result.baseCost, 0.01) // 50 * 3
            assertEquals(12.0, result.taxes, 0.01) // 150 * 0.08
        }
        
        @Test
        fun `daily rate pricing includes addons`() {
            val strategy = DailyRatePricing()
            val period = createWeekdayPeriod(days = 3)
            val addons = listOf(Addon.INSURANCE, Addon.GPS) // 15 + 5 = 20/day
            
            val result = strategy.calculatePrice(vehicle, period, addons)
            
            assertEquals(150.0, result.baseCost, 0.01)
            assertEquals(60.0, result.addonsCost, 0.01) // 20 * 3
            assertEquals(16.8, result.taxes, 0.01) // (150 + 60) * 0.08
        }
        
        @Test
        fun `weekend pricing applies multiplier`() {
            val strategy = WeekendPricing(weekendMultiplier = 1.20)
            val period = createWeekendPeriod() // 3 days with 2 weekend days
            
            val result = strategy.calculatePrice(vehicle, period, emptyList())
            
            // 1 weekday: 50, 2 weekend days: 50 * 1.2 * 2 = 120
            val expected = 50 + (50 * 1.20 * 2)
            assertEquals(expected, result.baseCost, 0.01)
        }
        
        @Test
        fun `long term pricing applies weekly discount`() {
            val strategy = LongTermDiscountPricing(weeklyDiscountPercent = 10.0)
            val period = createWeekdayPeriod(days = 7)
            
            val result = strategy.calculatePrice(vehicle, period, emptyList())
            
            assertEquals(350.0, result.baseCost, 0.01) // 50 * 7
            assertEquals(35.0, result.discounts, 0.01) // 350 * 0.10
        }
        
        @Test
        fun `long term pricing applies monthly discount`() {
            val strategy = LongTermDiscountPricing(monthlyDiscountPercent = 20.0)
            val period = createWeekdayPeriod(days = 30)
            
            val result = strategy.calculatePrice(vehicle, period, emptyList())
            
            assertEquals(1500.0, result.baseCost, 0.01) // 50 * 30
            assertEquals(300.0, result.discounts, 0.01) // 1500 * 0.20
        }
        
        @Test
        fun `seasonal pricing applies summer peak multiplier`() {
            val strategy = SeasonalPricing()
            val summerStart = LocalDateTime.of(2026, Month.JUNE, 15, 10, 0)
            val period = RentalPeriod(summerStart, summerStart.plusDays(3))
            
            val result = strategy.calculatePrice(vehicle, period, emptyList())
            
            val expected = 50 * 3 * 1.25 // Summer peak multiplier
            assertEquals(expected, result.baseCost, 0.01)
        }
        
        @Test
        fun `seasonal pricing applies off-peak discount`() {
            val strategy = SeasonalPricing()
            val offPeakStart = LocalDateTime.of(2026, Month.OCTOBER, 15, 10, 0)
            val period = RentalPeriod(offPeakStart, offPeakStart.plusDays(3))
            
            val result = strategy.calculatePrice(vehicle, period, emptyList())
            
            val expected = 50 * 3 * 0.90 // Off-peak discount
            assertEquals(expected, result.baseCost, 0.01)
        }
        
        @Test
        fun `membership pricing applies tier discount`() {
            val strategy = MembershipPricing()
            val customer = createTestCustomer(MembershipTier.GOLD) // 10% discount
            val period = createWeekdayPeriod(days = 3)
            
            val result = strategy.calculatePrice(vehicle, period, emptyList(), customer)
            
            assertEquals(150.0, result.baseCost, 0.01)
            assertEquals(15.0, result.discounts, 0.01) // 150 * 0.10
        }
        
        @Test
        fun `platinum membership gives highest discount`() {
            val strategy = MembershipPricing()
            val customer = createTestCustomer(MembershipTier.PLATINUM) // 15% discount
            val period = createWeekdayPeriod(days = 3)
            
            val result = strategy.calculatePrice(vehicle, period, emptyList(), customer)
            
            assertEquals(22.5, result.discounts, 0.01) // 150 * 0.15
        }
        
        @Test
        fun `pricing engine uses default strategy`() {
            val engine = PricingEngine(DailyRatePricing())
            val period = createWeekdayPeriod(days = 3)
            
            val result = engine.calculatePrice(vehicle, period, emptyList())
            
            assertEquals(150.0, result.baseCost, 0.01)
        }
        
        @Test
        fun `pricing engine uses vehicle-type-specific strategy`() {
            val engine = PricingEngine()
            engine.setStrategyForVehicleType(VehicleType.LUXURY, WeekendPricing(1.5))
            
            val luxuryVehicle = createTestVehicle(VehicleType.LUXURY, 100.0)
            val period = createWeekendPeriod()
            
            val result = engine.calculatePrice(luxuryVehicle, period, emptyList())
            
            // Weekend pricing applied to luxury vehicle
            assertTrue(result.baseCost > 300.0)
        }
        
        @Test
        fun `pricing engine calculates late return fee`() {
            val engine = PricingEngine()
            
            val fee = engine.calculateLateReturnFee(vehicle, 25) // 25 hours late
            
            // 25 - 1 (grace) = 24 hours = 1 day minimum
            // 50 * 1 * 1.5 = 75
            assertEquals(75.0, fee, 0.01)
        }
        
        @Test
        fun `no late fee within grace period`() {
            val engine = PricingEngine()
            
            val fee = engine.calculateLateReturnFee(vehicle, 1)
            
            assertEquals(0.0, fee, 0.01)
        }
        
        @Test
        fun `get quotes returns all pricing options`() {
            val engine = PricingEngine()
            val period = createWeekdayPeriod(days = 7)
            
            val quotes = engine.getQuotes(vehicle, period, emptyList())
            
            assertTrue(quotes.containsKey("Daily Rate"))
            assertTrue(quotes.containsKey("Weekend Premium"))
            assertTrue(quotes.containsKey("Long-Term Discount"))
            assertTrue(quotes.containsKey("Seasonal Pricing"))
            assertEquals(4, quotes.size)
        }
    }
    
    @Nested
    inner class RentalLifecycleTest {
        
        private lateinit var manager: StateMachineRentalManager
        private lateinit var vehicle: Vehicle
        private lateinit var customer: Customer
        
        @BeforeEach
        fun setup() {
            manager = StateMachineRentalManager()
            vehicle = createTestVehicle()
            customer = createTestCustomer()
            manager.addVehicle(vehicle)
        }
        
        @Test
        fun `vehicle starts as available`() {
            assertEquals(RentalStatus.AVAILABLE, vehicle.status)
            assertTrue(vehicle.isAvailable())
        }
        
        @Test
        fun `reserve changes status to reserved`() {
            val period = createWeekdayPeriod()
            
            val result = manager.reserve(vehicle.id, customer, period)
            
            assertTrue(result is RentalResult.Success)
            assertEquals(RentalStatus.RESERVED, vehicle.status)
        }
        
        @Test
        fun `cannot reserve unavailable vehicle`() {
            vehicle.status = RentalStatus.UNDER_MAINTENANCE
            val period = createWeekdayPeriod()
            
            val result = manager.reserve(vehicle.id, customer, period)
            
            assertTrue(result is RentalResult.InvalidTransition)
        }
        
        @Test
        fun `pickup changes status to picked up`() {
            val period = createWeekdayPeriod()
            val reserveResult = manager.reserve(vehicle.id, customer, period)
            assertTrue(reserveResult is RentalResult.Success)
            val rental = (reserveResult as RentalResult.Success).rental
            
            val result = manager.pickup(rental.id)
            
            assertTrue(result is RentalResult.Success)
            assertEquals(RentalStatus.PICKED_UP, vehicle.status)
        }
        
        @Test
        fun `cannot pickup without reservation`() {
            val result = manager.pickup("NONEXISTENT")
            
            assertTrue(result is RentalResult.NotFound)
        }
        
        @Test
        fun `return changes status to returned`() {
            val period = createWeekdayPeriod()
            val reserveResult = manager.reserve(vehicle.id, customer, period)
            val rental = (reserveResult as RentalResult.Success).rental
            manager.pickup(rental.id)
            
            val result = manager.returnVehicle(rental.id)
            
            assertTrue(result is RentalResult.Success)
            assertEquals(RentalStatus.RETURNED, vehicle.status)
        }
        
        @Test
        fun `late return adds fee and notifies`() {
            val period = createWeekdayPeriod()
            val reserveResult = manager.reserve(vehicle.id, customer, period)
            val rental = (reserveResult as RentalResult.Success).rental
            manager.pickup(rental.id)
            
            val lateReturnTime = period.end.plusHours(25) // 25 hours late
            val result = manager.returnVehicle(rental.id, lateReturnTime)
            
            assertTrue(result is RentalResult.LateReturn)
            val lateResult = result as RentalResult.LateReturn
            assertEquals(25, lateResult.hoursLate)
            assertTrue(lateResult.lateFee > 0)
        }
        
        @Test
        fun `damage assessment triggers maintenance`() {
            val period = createWeekdayPeriod()
            val reserveResult = manager.reserve(vehicle.id, customer, period)
            val rental = (reserveResult as RentalResult.Success).rental
            manager.pickup(rental.id)
            
            val damage = DamageAssessment(
                hasDamage = true,
                description = "Scratched bumper",
                repairCost = 500.0
            )
            val result = manager.returnVehicle(rental.id, damageAssessment = damage)
            
            assertTrue(result is RentalResult.DamageReported)
            assertEquals(RentalStatus.UNDER_MAINTENANCE, vehicle.status)
        }
        
        @Test
        fun `cancel reservation makes vehicle available`() {
            val period = createWeekdayPeriod()
            val reserveResult = manager.reserve(vehicle.id, customer, period)
            val rental = (reserveResult as RentalResult.Success).rental
            
            val result = manager.cancelReservation(rental.id)
            
            assertTrue(result is RentalResult.Success)
            assertEquals(RentalStatus.AVAILABLE, vehicle.status)
        }
        
        @Test
        fun `cannot cancel picked up rental`() {
            val period = createWeekdayPeriod()
            val reserveResult = manager.reserve(vehicle.id, customer, period)
            val rental = (reserveResult as RentalResult.Success).rental
            manager.pickup(rental.id)
            
            val result = manager.cancelReservation(rental.id)
            
            assertTrue(result is RentalResult.InvalidTransition)
        }
        
        @Test
        fun `send to maintenance from available`() {
            val result = manager.sendToMaintenance(vehicle.id, "Routine service")
            
            assertTrue(result is RentalResult.Success)
            assertEquals(RentalStatus.UNDER_MAINTENANCE, vehicle.status)
        }
        
        @Test
        fun `complete maintenance makes vehicle available`() {
            manager.sendToMaintenance(vehicle.id)
            
            val result = manager.completeMaintenanceAndMakeAvailable(vehicle.id)
            
            assertTrue(result is RentalResult.Success)
            assertEquals(RentalStatus.AVAILABLE, vehicle.status)
        }
        
        @Test
        fun `retire vehicle is final state`() {
            manager.sendToMaintenance(vehicle.id)
            
            val result = manager.retireVehicle(vehicle.id)
            
            assertTrue(result is RentalResult.Success)
            assertEquals(RentalStatus.RETIRED, vehicle.status)
        }
        
        @Test
        fun `cannot transition from retired`() {
            manager.sendToMaintenance(vehicle.id)
            manager.retireVehicle(vehicle.id)
            
            val result = manager.completeMaintenanceAndMakeAvailable(vehicle.id)
            
            assertTrue(result is RentalResult.InvalidTransition)
        }
        
        @Test
        fun `transition history is recorded`() {
            val period = createWeekdayPeriod()
            val reserveResult = manager.reserve(vehicle.id, customer, period)
            val rental = (reserveResult as RentalResult.Success).rental
            manager.pickup(rental.id)
            
            val history = manager.getTransitionHistory(rental.id)
            
            assertEquals(2, history.size)
            assertEquals(RentalStatus.AVAILABLE, history[0].from)
            assertEquals(RentalStatus.RESERVED, history[0].to)
            assertEquals(RentalStatus.RESERVED, history[1].from)
            assertEquals(RentalStatus.PICKED_UP, history[1].to)
        }
        
        @Test
        fun `observer notified on rental created`() {
            var notified = false
            manager.addObserver(object : RentalObserver {
                override fun onRentalCreated(rental: Rental) { notified = true }
                override fun onVehiclePickedUp(rental: Rental) {}
                override fun onVehicleReturned(rental: Rental) {}
                override fun onLateReturn(rental: Rental, hoursLate: Long) {}
                override fun onDamageReported(rental: Rental, assessment: DamageAssessment) {}
                override fun onMaintenanceRequired(vehicle: Vehicle) {}
            })
            
            manager.reserve(vehicle.id, customer, createWeekdayPeriod())
            
            assertTrue(notified)
        }
        
        @Test
        fun `get available vehicles filters correctly`() {
            val vehicle2 = createTestVehicle().copy(id = "VEH-002")
            val vehicle3 = createTestVehicle().copy(id = "VEH-003")
            manager.addVehicle(vehicle2)
            manager.addVehicle(vehicle3)
            
            manager.reserve(vehicle.id, customer, createWeekdayPeriod())
            manager.sendToMaintenance(vehicle2.id)
            
            val available = manager.getAvailableVehicles()
            
            assertEquals(1, available.size)
            assertEquals("VEH-003", available[0].id)
        }
    }
    
    @Nested
    inner class VehicleAvailabilityTest {
        
        private lateinit var manager: StateMachineRentalManager
        
        @BeforeEach
        fun setup() {
            manager = StateMachineRentalManager()
            
            listOf(
                createTestVehicle(VehicleType.ECONOMY).copy(id = "E1", dailyRate = 35.0),
                createTestVehicle(VehicleType.ECONOMY).copy(id = "E2", dailyRate = 35.0),
                createTestVehicle(VehicleType.COMPACT).copy(id = "C1", dailyRate = 45.0),
                createTestVehicle(VehicleType.SUV).copy(id = "S1", dailyRate = 75.0),
                createTestVehicle(VehicleType.LUXURY).copy(id = "L1", dailyRate = 150.0)
            ).forEach { manager.addVehicle(it) }
        }
        
        @Test
        fun `all vehicles initially available`() {
            val available = manager.getAvailableVehicles()
            assertEquals(5, available.size)
        }
        
        @Test
        fun `filter available by type`() {
            val economyVehicles = manager.getAvailableVehiclesByType(VehicleType.ECONOMY)
            assertEquals(2, economyVehicles.size)
            
            val luxuryVehicles = manager.getAvailableVehiclesByType(VehicleType.LUXURY)
            assertEquals(1, luxuryVehicles.size)
        }
        
        @Test
        fun `reserved vehicle not in available list`() {
            val customer = createTestCustomer()
            manager.reserve("E1", customer, createWeekdayPeriod())
            
            val economyVehicles = manager.getAvailableVehiclesByType(VehicleType.ECONOMY)
            assertEquals(1, economyVehicles.size)
            assertEquals("E2", economyVehicles[0].id)
        }
        
        @Test
        fun `vehicle under maintenance not available`() {
            manager.sendToMaintenance("S1")
            
            val suvVehicles = manager.getAvailableVehiclesByType(VehicleType.SUV)
            assertEquals(0, suvVehicles.size)
        }
        
        @Test
        fun `vehicle available again after maintenance`() {
            manager.sendToMaintenance("S1")
            manager.completeMaintenanceAndMakeAvailable("S1")
            
            val suvVehicles = manager.getAvailableVehiclesByType(VehicleType.SUV)
            assertEquals(1, suvVehicles.size)
        }
    }
    
    @Nested
    inner class FactoryVehicleCreationTest {
        
        @Test
        fun `economy factory creates correct vehicle`() {
            val factory = EconomyVehicleFactory()
            val vehicle = factory.createVehicle()
            
            assertEquals(VehicleType.ECONOMY, vehicle.type)
            assertEquals(35.0, vehicle.dailyRate)
            assertTrue(vehicle.features.contains("AC"))
            assertTrue(vehicle.features.contains("Radio"))
        }
        
        @Test
        fun `compact factory creates correct vehicle`() {
            val factory = CompactVehicleFactory()
            val vehicle = factory.createVehicle()
            
            assertEquals(VehicleType.COMPACT, vehicle.type)
            assertEquals(45.0, vehicle.dailyRate)
            assertTrue(vehicle.features.contains("Bluetooth"))
        }
        
        @Test
        fun `suv factory creates vehicle with 4WD`() {
            val factory = SUVVehicleFactory()
            val vehicle = factory.createVehicle()
            
            assertEquals(VehicleType.SUV, vehicle.type)
            assertEquals(75.0, vehicle.dailyRate)
            assertEquals(7, vehicle.passengerCapacity)
            assertTrue(vehicle.features.contains("4WD"))
        }
        
        @Test
        fun `luxury factory creates premium vehicle`() {
            val factory = LuxuryVehicleFactory()
            val vehicle = factory.createVehicle()
            
            assertEquals(VehicleType.LUXURY, vehicle.type)
            assertEquals(150.0, vehicle.dailyRate)
            assertEquals(FuelType.HYBRID, vehicle.fuelType)
            assertTrue(vehicle.features.contains("Leather Seats"))
            assertTrue(vehicle.features.contains("Heated Seats"))
        }
        
        @Test
        fun `van factory creates high-capacity vehicle`() {
            val factory = VanVehicleFactory()
            val vehicle = factory.createVehicle()
            
            assertEquals(VehicleType.VAN, vehicle.type)
            assertEquals(8, vehicle.passengerCapacity)
            assertTrue(vehicle.features.contains("Sliding Doors"))
        }
        
        @Test
        fun `motorcycle factory creates two-seater`() {
            val factory = MotorcycleVehicleFactory()
            val vehicle = factory.createVehicle()
            
            assertEquals(VehicleType.MOTORCYCLE, vehicle.type)
            assertEquals(2, vehicle.passengerCapacity)
            assertTrue(vehicle.features.contains("Helmet Included"))
        }
        
        @Test
        fun `factory accepts custom config`() {
            val factory = CompactVehicleFactory()
            val config = VehicleConfig(
                type = VehicleType.COMPACT,
                make = "Mazda",
                model = "3",
                year = 2024,
                dailyRate = 55.0
            )
            
            val vehicle = factory.createVehicle(config)
            
            assertEquals("Mazda", vehicle.make)
            assertEquals("3", vehicle.model)
            assertEquals(2024, vehicle.year)
            assertEquals(55.0, vehicle.dailyRate)
        }
        
        @Test
        fun `factory provider returns correct factory`() {
            val economyFactory = VehicleFactoryProvider.getFactory(VehicleType.ECONOMY)
            val luxuryFactory = VehicleFactoryProvider.getFactory(VehicleType.LUXURY)
            
            assertEquals(VehicleType.ECONOMY, economyFactory.getVehicleType())
            assertEquals(VehicleType.LUXURY, luxuryFactory.getVehicleType())
        }
        
        @Test
        fun `factory provider creates vehicle directly`() {
            val vehicle = VehicleFactoryProvider.createVehicle(VehicleType.SUV)
            
            assertEquals(VehicleType.SUV, vehicle.type)
            assertTrue(vehicle.id.startsWith("VEH-"))
        }
        
        @Test
        fun `electric vehicle factory adds EV features`() {
            val factory = ElectricVehicleFactory(VehicleType.COMPACT)
            val vehicle = factory.createVehicle()
            
            assertEquals(FuelType.ELECTRIC, vehicle.fuelType)
            assertTrue(vehicle.features.contains("Electric Motor"))
            assertTrue(vehicle.features.contains("Regenerative Braking"))
            assertTrue(vehicle.dailyRate > 45.0) // Premium over base
        }
    }
    
    @Nested
    inner class FleetManagementTest {
        
        private lateinit var manager: FactoryFleetManager
        
        @BeforeEach
        fun setup() {
            manager = FactoryFleetManager()
        }
        
        @Test
        fun `create single vehicle`() {
            val vehicle = manager.createVehicle(VehicleType.COMPACT)
            
            assertNotNull(vehicle)
            assertEquals(VehicleType.COMPACT, vehicle.type)
            assertEquals(1, manager.getAllVehicles().size)
        }
        
        @Test
        fun `create fleet of same type`() {
            val fleet = manager.createFleet(VehicleType.ECONOMY, 5)
            
            assertEquals(5, fleet.size)
            assertTrue(fleet.all { it.type == VehicleType.ECONOMY })
            assertEquals(5, manager.getAllVehicles().size)
        }
        
        @Test
        fun `create mixed fleet`() {
            val distribution = mapOf(
                VehicleType.ECONOMY to 3,
                VehicleType.COMPACT to 2,
                VehicleType.SUV to 1
            )
            
            val fleet = manager.createMixedFleet(distribution)
            
            assertEquals(6, fleet.size)
            assertEquals(3, fleet.count { it.type == VehicleType.ECONOMY })
            assertEquals(2, fleet.count { it.type == VehicleType.COMPACT })
            assertEquals(1, fleet.count { it.type == VehicleType.SUV })
        }
        
        @Test
        fun `get vehicles by type`() {
            manager.createFleet(VehicleType.ECONOMY, 3)
            manager.createFleet(VehicleType.SUV, 2)
            
            val economyVehicles = manager.getVehiclesByType(VehicleType.ECONOMY)
            val suvVehicles = manager.getVehiclesByType(VehicleType.SUV)
            
            assertEquals(3, economyVehicles.size)
            assertEquals(2, suvVehicles.size)
        }
        
        @Test
        fun `fleet statistics are accurate`() {
            manager.createFleet(VehicleType.ECONOMY, 3)
            manager.createFleet(VehicleType.LUXURY, 2)
            
            val stats = manager.getFleetStatistics()
            
            assertEquals(5, stats.totalVehicles)
            assertEquals(5, stats.availableVehicles)
            assertEquals(3, stats.vehiclesByType[VehicleType.ECONOMY])
            assertEquals(2, stats.vehiclesByType[VehicleType.LUXURY])
        }
        
        @Test
        fun `get pricing tiers`() {
            val tiers = manager.getPricingTiers()
            
            assertEquals(35.0, tiers[VehicleType.ECONOMY])
            assertEquals(45.0, tiers[VehicleType.COMPACT])
            assertEquals(75.0, tiers[VehicleType.SUV])
            assertEquals(150.0, tiers[VehicleType.LUXURY])
        }
        
        @Test
        fun `fleet builder creates correct fleet`() {
            val manager = FleetBuilder()
                .addEconomy(3)
                .addCompact(2)
                .addSUV(1)
                .addLuxury(1)
                .build()
            
            val stats = manager.getFleetStatistics()
            
            assertEquals(7, stats.totalVehicles)
            assertEquals(3, stats.vehiclesByType[VehicleType.ECONOMY])
            assertEquals(2, stats.vehiclesByType[VehicleType.COMPACT])
            assertEquals(1, stats.vehiclesByType[VehicleType.SUV])
            assertEquals(1, stats.vehiclesByType[VehicleType.LUXURY])
        }
        
        @Test
        fun `fleet builder adds locations`() {
            val location = Location("LOC-1", "Airport", "123 Airport Rd")
            
            val manager = FleetBuilder()
                .addLocation(location)
                .addEconomy(5)
                .build()
            
            val stats = manager.getFleetStatistics()
            
            assertEquals(1, stats.locationCount)
            assertNotNull(manager.getLocation("LOC-1"))
        }
    }
    
    @Nested
    inner class MultiLocationTest {
        
        private lateinit var manager: FactoryFleetManager
        private lateinit var airport: Location
        private lateinit var downtown: Location
        
        @BeforeEach
        fun setup() {
            airport = Location("LOC-AIR", "Airport", "123 Airport Rd")
            downtown = Location("LOC-DT", "Downtown", "456 Main St")
            
            manager = FleetBuilder()
                .addLocation(airport)
                .addLocation(downtown)
                .addEconomy(4)
                .addCompact(3)
                .addSUV(2)
                .build()
        }
        
        @Test
        fun `assign vehicle to location`() {
            val vehicle = manager.getAllVehicles().first()
            
            val result = manager.assignVehicleToLocation(vehicle.id, "LOC-AIR")
            
            assertTrue(result)
            assertTrue(airport.vehicles.contains(vehicle))
        }
        
        @Test
        fun `reassign vehicle moves to new location`() {
            val vehicle = manager.getAllVehicles().first()
            
            manager.assignVehicleToLocation(vehicle.id, "LOC-AIR")
            manager.assignVehicleToLocation(vehicle.id, "LOC-DT")
            
            assertFalse(airport.vehicles.contains(vehicle))
            assertTrue(downtown.vehicles.contains(vehicle))
        }
        
        @Test
        fun `location shows available vehicles`() {
            val vehicles = manager.getAllVehicles().take(3)
            vehicles.forEach { 
                manager.assignVehicleToLocation(it.id, "LOC-AIR")
            }
            
            val available = airport.getAvailableVehicles()
            
            assertEquals(3, available.size)
        }
        
        @Test
        fun `location filters vehicles by type`() {
            val vehicles = manager.getAllVehicles()
            vehicles.forEach { 
                manager.assignVehicleToLocation(it.id, "LOC-AIR")
            }
            
            val economyAtAirport = airport.getAvailableVehiclesByType(VehicleType.ECONOMY)
            
            assertEquals(4, economyAtAirport.size)
        }
        
        @Test
        fun `unavailable vehicle not in location available list`() {
            val vehicles = manager.getAllVehicles().take(3)
            vehicles.forEach { 
                manager.assignVehicleToLocation(it.id, "LOC-AIR")
            }
            
            vehicles.first().status = RentalStatus.RESERVED
            
            val available = airport.getAvailableVehicles()
            
            assertEquals(2, available.size)
        }
    }
    
    @Nested
    inner class LateReturnChargesTest {
        
        private lateinit var manager: StateMachineRentalManager
        private lateinit var vehicle: Vehicle
        private lateinit var customer: Customer
        
        @BeforeEach
        fun setup() {
            manager = StateMachineRentalManager()
            vehicle = createTestVehicle(dailyRate = 50.0)
            customer = createTestCustomer()
            manager.addVehicle(vehicle)
        }
        
        @Test
        fun `no fee within grace period`() {
            val period = createWeekdayPeriod()
            val reserveResult = manager.reserve(vehicle.id, customer, period)
            val rental = (reserveResult as RentalResult.Success).rental
            manager.pickup(rental.id)
            
            val returnTime = period.end.plusMinutes(30) // 30 mins late
            val result = manager.returnVehicle(rental.id, returnTime)
            
            assertTrue(result is RentalResult.Success)
        }
        
        @Test
        fun `fee after grace period`() {
            val period = createWeekdayPeriod()
            val reserveResult = manager.reserve(vehicle.id, customer, period)
            val rental = (reserveResult as RentalResult.Success).rental
            manager.pickup(rental.id)
            
            val returnTime = period.end.plusHours(3) // 3 hours late
            val result = manager.returnVehicle(rental.id, returnTime)
            
            assertTrue(result is RentalResult.LateReturn)
            val lateResult = result as RentalResult.LateReturn
            assertEquals(3, lateResult.hoursLate)
            assertTrue(lateResult.lateFee > 0)
        }
        
        @Test
        fun `full day late fee for 24+ hours`() {
            val period = createWeekdayPeriod()
            val reserveResult = manager.reserve(vehicle.id, customer, period)
            val rental = (reserveResult as RentalResult.Success).rental
            manager.pickup(rental.id)
            
            val returnTime = period.end.plusHours(25)
            val result = manager.returnVehicle(rental.id, returnTime)
            
            val lateResult = result as RentalResult.LateReturn
            // 24 hours late after grace = 1 day * daily rate * 1.5
            assertEquals(75.0, lateResult.lateFee, 0.01)
        }
        
        @Test
        fun `damage fee added to total`() {
            val period = createWeekdayPeriod()
            val reserveResult = manager.reserve(vehicle.id, customer, period)
            val rental = (reserveResult as RentalResult.Success).rental
            manager.pickup(rental.id)
            
            val damage = DamageAssessment(hasDamage = true, repairCost = 200.0)
            val result = manager.returnVehicle(rental.id, damageAssessment = damage)
            
            assertTrue(result is RentalResult.DamageReported)
            val damageResult = result as RentalResult.DamageReported
            assertEquals(200.0, damageResult.assessment.repairCost)
        }
    }
    
    @Nested
    inner class RentalPeriodTest {
        
        @Test
        fun `weekday period has no weekend days`() {
            val monday = LocalDateTime.of(2026, Month.MARCH, 23, 10, 0)
            val wednesday = monday.plusDays(2)
            val period = RentalPeriod(monday, wednesday)
            
            assertEquals(0, period.getWeekendDays())
            assertFalse(period.isWeekend())
        }
        
        @Test
        fun `weekend period counts saturday and sunday`() {
            val friday = LocalDateTime.of(2026, Month.MARCH, 27, 10, 0)
            val sunday = friday.plusDays(2)
            val period = RentalPeriod(friday, sunday)
            
            assertEquals(2, period.getWeekendDays())
            assertTrue(period.isWeekend())
        }
        
        @Test
        fun `long term is 7+ days`() {
            val start = LocalDateTime.now()
            
            val shortPeriod = RentalPeriod(start, start.plusDays(5))
            val longPeriod = RentalPeriod(start, start.plusDays(7))
            
            assertFalse(shortPeriod.isLongTerm())
            assertTrue(longPeriod.isLongTerm())
        }
        
        @Test
        fun `minimum rental is 1 day`() {
            val start = LocalDateTime.now()
            val period = RentalPeriod(start, start.plusHours(12))
            
            assertEquals(1, period.getDays())
        }
    }
    
    @Nested
    inner class AddonTest {
        
        @Test
        fun `predefined addons have correct prices`() {
            assertEquals(15.0, Addon.INSURANCE.dailyPrice)
            assertEquals(5.0, Addon.GPS.dailyPrice)
            assertEquals(8.0, Addon.CHILD_SEAT.dailyPrice)
            assertEquals(10.0, Addon.ADDITIONAL_DRIVER.dailyPrice)
            assertEquals(7.0, Addon.ROADSIDE_ASSISTANCE.dailyPrice)
        }
        
        @Test
        fun `multiple addons combine correctly`() {
            val strategy = DailyRatePricing()
            val vehicle = createTestVehicle(dailyRate = 50.0)
            val period = createWeekdayPeriod(days = 3)
            val addons = listOf(
                Addon.INSURANCE,
                Addon.GPS,
                Addon.CHILD_SEAT
            )
            
            val result = strategy.calculatePrice(vehicle, period, addons)
            
            // (15 + 5 + 8) * 3 = 84
            assertEquals(84.0, result.addonsCost, 0.01)
        }
    }
}
