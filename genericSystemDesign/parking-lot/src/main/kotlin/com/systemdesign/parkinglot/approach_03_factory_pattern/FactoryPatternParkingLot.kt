package com.systemdesign.parkinglot.approach_03_factory_pattern

import com.systemdesign.parkinglot.common.*

/**
 * Approach 3: Factory Pattern for Spot and Vehicle Creation
 * 
 * Factory pattern is used to create spots, vehicles, and parking lot configurations.
 * Useful for creating different lot configurations (mall, airport, hospital).
 * 
 * Pattern: Factory Method + Abstract Factory
 * 
 * Trade-offs:
 * + Encapsulates object creation logic
 * + Easy to create different lot configurations
 * + Creation logic can be extended without modifying clients
 * - Additional factory classes add complexity
 * - Factory needs to be updated for new types
 * 
 * When to use:
 * - When object creation is complex
 * - When different configurations of the same system are needed
 * - When creation logic may change independently of usage
 * 
 * Extensibility:
 * - New lot type: Implement ParkingLotFactory interface
 * - New spot configuration: Modify factory implementation
 */

/** Factory for creating parking spots */
interface SpotFactory {
    fun createSpot(id: String, floor: Int, row: Int, number: Int): ParkingSpot
}

class MotorcycleSpotFactory : SpotFactory {
    override fun createSpot(id: String, floor: Int, row: Int, number: Int): ParkingSpot {
        return ParkingSpot(id, SpotType.MOTORCYCLE, floor, row, number)
    }
}

class CompactSpotFactory : SpotFactory {
    override fun createSpot(id: String, floor: Int, row: Int, number: Int): ParkingSpot {
        return ParkingSpot(id, SpotType.COMPACT, floor, row, number)
    }
}

class RegularSpotFactory : SpotFactory {
    override fun createSpot(id: String, floor: Int, row: Int, number: Int): ParkingSpot {
        return ParkingSpot(id, SpotType.REGULAR, floor, row, number)
    }
}

class LargeSpotFactory : SpotFactory {
    override fun createSpot(id: String, floor: Int, row: Int, number: Int): ParkingSpot {
        return ParkingSpot(id, SpotType.LARGE, floor, row, number)
    }
}

class ElectricSpotFactory : SpotFactory {
    override fun createSpot(id: String, floor: Int, row: Int, number: Int): ParkingSpot {
        return ParkingSpot(id, SpotType.ELECTRIC, floor, row, number)
    }
}

class HandicappedSpotFactory : SpotFactory {
    override fun createSpot(id: String, floor: Int, row: Int, number: Int): ParkingSpot {
        return ParkingSpot(id, SpotType.HANDICAPPED, floor, row, number)
    }
}

/** Registry for spot factories */
object SpotFactoryRegistry {
    private val factories = mutableMapOf<SpotType, SpotFactory>(
        SpotType.MOTORCYCLE to MotorcycleSpotFactory(),
        SpotType.COMPACT to CompactSpotFactory(),
        SpotType.REGULAR to RegularSpotFactory(),
        SpotType.LARGE to LargeSpotFactory(),
        SpotType.ELECTRIC to ElectricSpotFactory(),
        SpotType.HANDICAPPED to HandicappedSpotFactory()
    )
    
    fun getFactory(type: SpotType): SpotFactory {
        return factories[type] ?: throw IllegalArgumentException("Unknown spot type: $type")
    }
    
    fun registerFactory(type: SpotType, factory: SpotFactory) {
        factories[type] = factory
    }
}

/** Configuration for a parking floor */
data class FloorConfiguration(
    val floorNumber: Int,
    val spotCounts: Map<SpotType, Int>
)

/** Abstract factory for creating complete parking lots */
interface ParkingLotFactory {
    fun createFloors(): List<ParkingFloor>
    fun createEntryGates(): List<Gate>
    fun createExitGates(): List<Gate>
}

/** Mall parking lot factory - mixed spot types, multiple gates */
class MallParkingLotFactory(
    private val floorCount: Int = 3,
    private val spotsPerFloor: Int = 100
) : ParkingLotFactory {
    
    override fun createFloors(): List<ParkingFloor> {
        return (1..floorCount).map { floorNum ->
            val floor = ParkingFloor(floorNum)
            var spotNum = 0
            
            // 10% motorcycle, 30% compact, 40% regular, 10% large, 5% EV, 5% handicapped
            val config = mapOf(
                SpotType.MOTORCYCLE to (spotsPerFloor * 0.10).toInt(),
                SpotType.COMPACT to (spotsPerFloor * 0.30).toInt(),
                SpotType.REGULAR to (spotsPerFloor * 0.40).toInt(),
                SpotType.LARGE to (spotsPerFloor * 0.10).toInt(),
                SpotType.ELECTRIC to (spotsPerFloor * 0.05).toInt(),
                SpotType.HANDICAPPED to (spotsPerFloor * 0.05).toInt()
            )
            
            config.forEach { (type, count) ->
                val factory = SpotFactoryRegistry.getFactory(type)
                repeat(count) {
                    val row = spotNum / 20
                    val num = spotNum % 20
                    floor.spots.add(
                        factory.createSpot("F${floorNum}-${type.name[0]}$spotNum", floorNum, row, num)
                    )
                    spotNum++
                }
            }
            
            floor
        }
    }
    
    override fun createEntryGates(): List<Gate> {
        return listOf(
            Gate("ENTRY-1", GateType.ENTRY),
            Gate("ENTRY-2", GateType.ENTRY)
        )
    }
    
    override fun createExitGates(): List<Gate> {
        return listOf(
            Gate("EXIT-1", GateType.EXIT),
            Gate("EXIT-2", GateType.EXIT)
        )
    }
}

/** Airport parking lot factory - more large spots, EV charging */
class AirportParkingLotFactory(
    private val floorCount: Int = 5,
    private val spotsPerFloor: Int = 200
) : ParkingLotFactory {
    
    override fun createFloors(): List<ParkingFloor> {
        return (1..floorCount).map { floorNum ->
            val floor = ParkingFloor(floorNum)
            var spotNum = 0
            
            // More large spots for luggage, more EV for long-term parking
            val config = mapOf(
                SpotType.MOTORCYCLE to (spotsPerFloor * 0.05).toInt(),
                SpotType.COMPACT to (spotsPerFloor * 0.20).toInt(),
                SpotType.REGULAR to (spotsPerFloor * 0.35).toInt(),
                SpotType.LARGE to (spotsPerFloor * 0.25).toInt(),
                SpotType.ELECTRIC to (spotsPerFloor * 0.10).toInt(),
                SpotType.HANDICAPPED to (spotsPerFloor * 0.05).toInt()
            )
            
            config.forEach { (type, count) ->
                val factory = SpotFactoryRegistry.getFactory(type)
                repeat(count) {
                    val row = spotNum / 25
                    val num = spotNum % 25
                    floor.spots.add(
                        factory.createSpot("F${floorNum}-${type.name[0]}$spotNum", floorNum, row, num)
                    )
                    spotNum++
                }
            }
            
            floor
        }
    }
    
    override fun createEntryGates(): List<Gate> {
        return listOf(
            Gate("ENTRY-TERMINAL-A", GateType.ENTRY),
            Gate("ENTRY-TERMINAL-B", GateType.ENTRY),
            Gate("ENTRY-TERMINAL-C", GateType.ENTRY)
        )
    }
    
    override fun createExitGates(): List<Gate> {
        return listOf(
            Gate("EXIT-TERMINAL-A", GateType.EXIT),
            Gate("EXIT-TERMINAL-B", GateType.EXIT),
            Gate("EXIT-TERMINAL-C", GateType.EXIT)
        )
    }
}

/** Hospital parking lot factory - more handicapped spots, close to entrance */
class HospitalParkingLotFactory(
    private val floorCount: Int = 2,
    private val spotsPerFloor: Int = 80
) : ParkingLotFactory {
    
    override fun createFloors(): List<ParkingFloor> {
        return (1..floorCount).map { floorNum ->
            val floor = ParkingFloor(floorNum)
            var spotNum = 0
            
            // More handicapped spots, no motorcycle spots
            val config = mapOf(
                SpotType.COMPACT to (spotsPerFloor * 0.25).toInt(),
                SpotType.REGULAR to (spotsPerFloor * 0.45).toInt(),
                SpotType.LARGE to (spotsPerFloor * 0.10).toInt(),
                SpotType.ELECTRIC to (spotsPerFloor * 0.05).toInt(),
                SpotType.HANDICAPPED to (spotsPerFloor * 0.15).toInt() // 15% for hospital
            )
            
            // Handicapped spots first (closest to entrance)
            val orderedTypes = listOf(
                SpotType.HANDICAPPED,
                SpotType.REGULAR,
                SpotType.COMPACT,
                SpotType.LARGE,
                SpotType.ELECTRIC
            )
            
            orderedTypes.forEach { type ->
                val count = config[type] ?: 0
                val factory = SpotFactoryRegistry.getFactory(type)
                repeat(count) {
                    val row = spotNum / 20
                    val num = spotNum % 20
                    floor.spots.add(
                        factory.createSpot("F${floorNum}-${type.name[0]}$spotNum", floorNum, row, num)
                    )
                    spotNum++
                }
            }
            
            floor
        }
    }
    
    override fun createEntryGates(): List<Gate> {
        return listOf(
            Gate("ENTRY-EMERGENCY", GateType.ENTRY),
            Gate("ENTRY-VISITOR", GateType.ENTRY)
        )
    }
    
    override fun createExitGates(): List<Gate> {
        return listOf(
            Gate("EXIT-MAIN", GateType.EXIT)
        )
    }
}

/**
 * Parking lot builder using factory
 */
class ParkingLotBuilder {
    private var factory: ParkingLotFactory = MallParkingLotFactory()
    private var pricingStrategy: PricingStrategy? = null
    private var assignmentStrategy: SpotAssignmentStrategy? = null
    
    fun withFactory(factory: ParkingLotFactory): ParkingLotBuilder {
        this.factory = factory
        return this
    }
    
    fun withMallConfiguration(floors: Int = 3, spotsPerFloor: Int = 100): ParkingLotBuilder {
        this.factory = MallParkingLotFactory(floors, spotsPerFloor)
        return this
    }
    
    fun withAirportConfiguration(floors: Int = 5, spotsPerFloor: Int = 200): ParkingLotBuilder {
        this.factory = AirportParkingLotFactory(floors, spotsPerFloor)
        return this
    }
    
    fun withHospitalConfiguration(floors: Int = 2, spotsPerFloor: Int = 80): ParkingLotBuilder {
        this.factory = HospitalParkingLotFactory(floors, spotsPerFloor)
        return this
    }
    
    fun withPricing(strategy: PricingStrategy): ParkingLotBuilder {
        this.pricingStrategy = strategy
        return this
    }
    
    fun withAssignment(strategy: SpotAssignmentStrategy): ParkingLotBuilder {
        this.assignmentStrategy = strategy
        return this
    }
    
    fun build(lotId: String): FactoryBasedParkingLot {
        return FactoryBasedParkingLot(
            lotId = lotId,
            floors = factory.createFloors(),
            entryGates = factory.createEntryGates(),
            exitGates = factory.createExitGates(),
            pricingStrategy = pricingStrategy,
            assignmentStrategy = assignmentStrategy
        )
    }
}

/**
 * Simplified parking lot for factory demonstration
 */
class FactoryBasedParkingLot(
    val lotId: String,
    val floors: List<ParkingFloor>,
    val entryGates: List<Gate>,
    val exitGates: List<Gate>,
    private val pricingStrategy: PricingStrategy? = null,
    private val assignmentStrategy: SpotAssignmentStrategy? = null
) {
    fun getStats(): ParkingLotStats {
        val allSpots = floors.flatMap { it.spots }
        val total = allSpots.size
        val occupied = allSpots.count { it.isOccupied }
        
        return ParkingLotStats(
            totalSpots = total,
            occupiedSpots = occupied,
            availableSpots = total - occupied,
            spotsPerType = allSpots.groupBy { it.type }.mapValues { it.value.size },
            availablePerType = allSpots.filter { !it.isOccupied }
                .groupBy { it.type }
                .mapValues { it.value.size }
        )
    }
    
    fun getFloorStats(floorNumber: Int): ParkingFloor? {
        return floors.find { it.floorNumber == floorNumber }
    }
}
