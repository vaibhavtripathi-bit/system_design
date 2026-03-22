package com.systemdesign.parkinglot

import com.systemdesign.parkinglot.common.*
import com.systemdesign.parkinglot.approach_01_strategy_assignment.*
import com.systemdesign.parkinglot.approach_02_decorator_pricing.*
import com.systemdesign.parkinglot.approach_03_factory_pattern.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import java.time.LocalDateTime

class ParkingLotTest {
    
    private fun createTestFloors(): List<ParkingFloor> {
        val floor1 = ParkingFloor(1)
        repeat(5) { i ->
            floor1.spots.add(ParkingSpot("F1-R$i", SpotType.REGULAR, 1, 0, i))
        }
        repeat(2) { i ->
            floor1.spots.add(ParkingSpot("F1-E$i", SpotType.ELECTRIC, 1, 1, i))
        }
        repeat(2) { i ->
            floor1.spots.add(ParkingSpot("F1-H$i", SpotType.HANDICAPPED, 1, 1, i + 2))
        }
        repeat(2) { i ->
            floor1.spots.add(ParkingSpot("F1-C$i", SpotType.COMPACT, 1, 2, i))
        }
        
        val floor2 = ParkingFloor(2)
        repeat(5) { i ->
            floor2.spots.add(ParkingSpot("F2-R$i", SpotType.REGULAR, 2, 0, i))
        }
        repeat(3) { i ->
            floor2.spots.add(ParkingSpot("F2-L$i", SpotType.LARGE, 2, 1, i))
        }
        
        return listOf(floor1, floor2)
    }
    
    private fun createTestGates(): Pair<List<Gate>, List<Gate>> {
        val entryGates = listOf(Gate("E1", GateType.ENTRY), Gate("E2", GateType.ENTRY))
        val exitGates = listOf(Gate("X1", GateType.EXIT))
        return Pair(entryGates, exitGates)
    }
    
    @Nested
    inner class SpotAssignmentStrategyTest {
        
        private lateinit var lot: StrategyParkingLot
        private lateinit var floors: List<ParkingFloor>
        
        @BeforeEach
        fun setup() {
            floors = createTestFloors()
            val (entry, exit) = createTestGates()
            lot = StrategyParkingLot("LOT-1", floors, entry, exit)
        }
        
        @Test
        fun `vehicle enters and gets ticket`() {
            val vehicle = Vehicle("ABC-123", VehicleType.CAR)
            val ticket = lot.enter(vehicle, "E1")
            
            assertNotNull(ticket)
            assertEquals(vehicle, ticket!!.vehicle)
            assertNotNull(ticket.spot)
        }
        
        @Test
        fun `vehicle exits and pays fee`() {
            val vehicle = Vehicle("ABC-123", VehicleType.CAR)
            val ticket = lot.enter(vehicle, "E1")!!
            
            val exitedTicket = lot.exit(ticket.ticketId, "X1")
            
            assertNotNull(exitedTicket)
            assertNotNull(exitedTicket!!.exitTime)
            assertTrue(exitedTicket.fee >= 0)
        }
        
        @Test
        fun `same vehicle cannot park twice`() {
            val vehicle = Vehicle("ABC-123", VehicleType.CAR)
            lot.enter(vehicle, "E1")
            
            val secondTicket = lot.enter(vehicle, "E1")
            
            assertNull(secondTicket)
        }
        
        @Test
        fun `nearest spot strategy assigns closest spot`() {
            lot.setAssignmentStrategy(NearestSpotStrategy())
            val vehicle = Vehicle("ABC-123", VehicleType.CAR)
            
            val ticket = lot.enter(vehicle, "E1")
            
            assertEquals(1, ticket!!.spot.floor)
        }
        
        @Test
        fun `EV priority strategy assigns EV spot to electric car`() {
            lot.setAssignmentStrategy(EVPriorityStrategy())
            val vehicle = Vehicle("EV-001", VehicleType.ELECTRIC_CAR)
            
            val ticket = lot.enter(vehicle, "E1")
            
            assertEquals(SpotType.ELECTRIC, ticket!!.spot.type)
        }
        
        @Test
        fun `EV fallback to regular when no EV spots`() {
            lot.setAssignmentStrategy(EVPriorityStrategy())
            
            // Fill all EV spots
            repeat(2) { i ->
                lot.enter(Vehicle("EV-$i", VehicleType.ELECTRIC_CAR), "E1")
            }
            
            val vehicle = Vehicle("EV-NEW", VehicleType.ELECTRIC_CAR)
            val ticket = lot.enter(vehicle, "E1")
            
            assertNotNull(ticket)
            assertNotEquals(SpotType.ELECTRIC, ticket!!.spot.type)
        }
        
        @Test
        fun `lot full returns null ticket`() {
            // Fill all spots
            repeat(19) { i ->
                lot.enter(Vehicle("CAR-$i", VehicleType.CAR), "E1")
            }
            
            val vehicle = Vehicle("OVERFLOW", VehicleType.CAR)
            val ticket = lot.enter(vehicle, "E1")
            
            assertNull(ticket)
        }
        
        @Test
        fun `lost ticket exit with penalty`() {
            val vehicle = Vehicle("LOST-123", VehicleType.CAR)
            lot.enter(vehicle, "E1")
            
            val ticket = lot.exitWithLostTicket("LOST-123", "X1")
            
            assertNotNull(ticket)
            assertTrue(ticket!!.isLost)
            assertEquals(50.0, ticket.fee)
        }
        
        @Test
        fun `observer notified on entry and exit`() {
            var enteredCount = 0
            var exitedCount = 0
            
            lot.addObserver(object : ParkingLotObserver {
                override fun onVehicleEntered(ticket: ParkingTicket) { enteredCount++ }
                override fun onVehicleExited(ticket: ParkingTicket) { exitedCount++ }
                override fun onLotFull() {}
                override fun onLotAvailable() {}
            })
            
            val ticket = lot.enter(Vehicle("OBS-1", VehicleType.CAR), "E1")
            lot.exit(ticket!!.ticketId, "X1")
            
            assertEquals(1, enteredCount)
            assertEquals(1, exitedCount)
        }
        
        @Test
        fun `stats are accurate`() {
            lot.enter(Vehicle("CAR-1", VehicleType.CAR), "E1")
            lot.enter(Vehicle("CAR-2", VehicleType.CAR), "E1")
            
            val stats = lot.getStats()
            
            assertEquals(19, stats.totalSpots)
            assertEquals(2, stats.occupiedSpots)
            assertEquals(17, stats.availableSpots)
        }
    }
    
    @Nested
    inner class DecoratorPricingTest {
        
        @Test
        fun `base pricing calculates hourly rate`() {
            val strategy = BasePricingStrategy(hourlyRate = 3.0)
            val ticket = createTestTicket(hoursParked = 2)
            
            val fee = strategy.calculateFee(ticket)
            
            assertEquals(9.0, fee) // 3 hours (2 + partial) * 3.0
        }
        
        @Test
        fun `daily max is applied`() {
            val strategy = BasePricingStrategy(hourlyRate = 5.0, dailyMax = 25.0)
            val ticket = createTestTicket(hoursParked = 10)
            
            val fee = strategy.calculateFee(ticket)
            
            assertEquals(25.0, fee)
        }
        
        @Test
        fun `peak hour surcharge is applied`() {
            val base = BasePricingStrategy(hourlyRate = 2.0)
            val strategy = PeakHourPricingDecorator(base, surchargePercent = 0.25)
            
            val peakTicket = createTestTicket(hoursParked = 1, entryHour = 10)
            val offPeakTicket = createTestTicket(hoursParked = 1, entryHour = 22)
            
            val peakFee = strategy.calculateFee(peakTicket)
            val offPeakFee = strategy.calculateFee(offPeakTicket)
            
            assertEquals(5.0, peakFee) // 2 * 2 * 1.25
            assertEquals(4.0, offPeakFee) // 2 * 2 * 1.0
        }
        
        @Test
        fun `weekend surcharge is applied`() {
            val base = BasePricingStrategy(hourlyRate = 2.0)
            val strategy = WeekendPricingDecorator(base, surchargePercent = 0.15)
            
            // Create weekend ticket (Saturday)
            val weekendEntry = LocalDateTime.of(2024, 3, 23, 10, 0) // Saturday
            val weekdayEntry = LocalDateTime.of(2024, 3, 25, 10, 0) // Monday
            
            val weekendTicket = createTestTicketWithEntry(1, weekendEntry)
            val weekdayTicket = createTestTicketWithEntry(1, weekdayEntry)
            
            val weekendFee = strategy.calculateFee(weekendTicket)
            val weekdayFee = strategy.calculateFee(weekdayTicket)
            
            assertEquals(4.6, weekendFee, 0.01) // 4 * 1.15
            assertEquals(4.0, weekdayFee, 0.01) // 4 * 1.0
        }
        
        @Test
        fun `loyalty discount is applied`() {
            val loyaltyChecker = SimpleLoyaltyChecker(setOf("LOYAL-001"))
            val base = BasePricingStrategy(hourlyRate = 2.0)
            val strategy = LoyaltyDiscountDecorator(base, 0.10, loyaltyChecker)
            
            val loyalTicket = createTestTicket(hoursParked = 1, licensePlate = "LOYAL-001")
            val regularTicket = createTestTicket(hoursParked = 1, licensePlate = "REG-001")
            
            val loyalFee = strategy.calculateFee(loyalTicket)
            val regularFee = strategy.calculateFee(regularTicket)
            
            assertEquals(3.6, loyalFee, 0.01) // 4 * 0.9
            assertEquals(4.0, regularFee, 0.01)
        }
        
        @Test
        fun `lost ticket penalty overrides calculation`() {
            val base = BasePricingStrategy(hourlyRate = 2.0)
            val strategy = LostTicketDecorator(base, penalty = 50.0)
            
            val lostTicket = createTestTicket(hoursParked = 1).copy(isLost = true)
            val normalTicket = createTestTicket(hoursParked = 1)
            
            assertEquals(50.0, strategy.calculateFee(lostTicket))
            assertEquals(4.0, strategy.calculateFee(normalTicket))
        }
        
        @Test
        fun `first hour free decorator`() {
            val base = BasePricingStrategy(hourlyRate = 2.0)
            val strategy = FirstHourFreeDecorator(base)
            
            val shortTicket = createTestTicket(minutesParked = 45)
            val longTicket = createTestTicket(minutesParked = 90)
            
            assertEquals(0.0, strategy.calculateFee(shortTicket))
            assertEquals(4.0, strategy.calculateFee(longTicket))
        }
        
        @Test
        fun `stacked decorators apply in order`() {
            val strategy = PricingStrategyBuilder()
                .withBase(hourlyRate = 2.0, dailyMax = 30.0)
                .withPeakHours(surchargePercent = 0.25)
                .withLostTicketPenalty(50.0)
                .build()
            
            val peakTicket = createTestTicket(hoursParked = 2, entryHour = 10)
            
            // Base: 3 * 2 = 6, Peak: 6 * 1.25 = 7.5
            assertEquals(7.5, strategy.calculateFee(peakTicket), 0.01)
        }
        
        private fun createTestTicket(
            hoursParked: Int = 1,
            minutesParked: Int? = null,
            entryHour: Int = 12,
            licensePlate: String = "TEST-001"
        ): ParkingTicket {
            val baseDate = LocalDateTime.of(2024, 3, 25, 12, 0) // Monday at noon
            val entryTime = if (minutesParked != null) {
                baseDate
            } else {
                baseDate.withHour(entryHour)
            }
            
            val exitTime = if (minutesParked != null) {
                entryTime.plusMinutes(minutesParked.toLong())
            } else {
                entryTime.plusHours(hoursParked.toLong())
            }
            
            return ParkingTicket(
                vehicle = Vehicle(licensePlate, VehicleType.CAR),
                spot = ParkingSpot("S1", SpotType.REGULAR, 1, 0, 0),
                entryTime = entryTime,
                exitTime = exitTime
            )
        }
        
        private fun createTestTicketWithEntry(hoursParked: Int, entryTime: LocalDateTime): ParkingTicket {
            return ParkingTicket(
                vehicle = Vehicle("TEST-001", VehicleType.CAR),
                spot = ParkingSpot("S1", SpotType.REGULAR, 1, 0, 0),
                entryTime = entryTime,
                exitTime = entryTime.plusHours(hoursParked.toLong())
            )
        }
    }
    
    @Nested
    inner class FactoryPatternTest {
        
        @Test
        fun `mall factory creates correct spot distribution`() {
            val lot = ParkingLotBuilder()
                .withMallConfiguration(floors = 2, spotsPerFloor = 100)
                .build("MALL-LOT")
            
            val stats = lot.getStats()
            
            assertEquals(2, lot.floors.size)
            assertTrue(stats.totalSpots > 150)
        }
        
        @Test
        fun `airport factory creates more large spots`() {
            val lot = ParkingLotBuilder()
                .withAirportConfiguration(floors = 2, spotsPerFloor = 100)
                .build("AIRPORT-LOT")
            
            val stats = lot.getStats()
            val largeSpots = stats.spotsPerType[SpotType.LARGE] ?: 0
            
            assertTrue(largeSpots >= 40) // 25% of ~200
        }
        
        @Test
        fun `hospital factory creates more handicapped spots`() {
            val lot = ParkingLotBuilder()
                .withHospitalConfiguration(floors = 2, spotsPerFloor = 100)
                .build("HOSPITAL-LOT")
            
            val stats = lot.getStats()
            val handicappedSpots = stats.spotsPerType[SpotType.HANDICAPPED] ?: 0
            
            assertTrue(handicappedSpots >= 25) // 15% of ~200
        }
        
        @Test
        fun `factory creates correct number of gates`() {
            val mallLot = ParkingLotBuilder()
                .withMallConfiguration()
                .build("MALL")
            
            val airportLot = ParkingLotBuilder()
                .withAirportConfiguration()
                .build("AIRPORT")
            
            assertEquals(2, mallLot.entryGates.size)
            assertEquals(2, mallLot.exitGates.size)
            assertEquals(3, airportLot.entryGates.size)
            assertEquals(3, airportLot.exitGates.size)
        }
        
        @Test
        fun `spot factory registry returns correct factory`() {
            val regularFactory = SpotFactoryRegistry.getFactory(SpotType.REGULAR)
            val spot = regularFactory.createSpot("S1", 1, 0, 0)
            
            assertEquals(SpotType.REGULAR, spot.type)
        }
        
        @Test
        fun `custom factory can be registered`() {
            val customFactory = object : SpotFactory {
                override fun createSpot(id: String, floor: Int, row: Int, number: Int): ParkingSpot {
                    return ParkingSpot(id, SpotType.LARGE, floor, row, number)
                }
            }
            
            SpotFactoryRegistry.registerFactory(SpotType.LARGE, customFactory)
            val factory = SpotFactoryRegistry.getFactory(SpotType.LARGE)
            
            assertEquals(customFactory, factory)
        }
    }
}
