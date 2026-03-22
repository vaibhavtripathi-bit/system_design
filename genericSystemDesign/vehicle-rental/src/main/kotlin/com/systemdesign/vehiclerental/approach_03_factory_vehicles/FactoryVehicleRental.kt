package com.systemdesign.vehiclerental.approach_03_factory_vehicles

import com.systemdesign.vehiclerental.common.*
import java.util.UUID

/**
 * Approach 3: Factory Pattern for Vehicle Creation
 * 
 * Different vehicle types are created through factory methods.
 * Encapsulates complex object creation with proper defaults.
 * 
 * Pattern: Factory Pattern (Abstract Factory + Factory Method)
 * 
 * Trade-offs:
 * + Centralized vehicle creation logic
 * + Consistent configuration per vehicle type
 * + Easy to add new vehicle categories
 * - Factory classes can become large
 * - May need multiple factories for different concerns
 * 
 * When to use:
 * - When objects have complex initialization
 * - When creation logic varies by type
 * - When consistent defaults are needed
 * 
 * Extensibility:
 * - New vehicle type: Add factory method and enum value
 * - New configuration: Add to VehicleConfig
 */

/** Vehicle factory interface */
interface VehicleFactory {
    fun createVehicle(config: VehicleConfig? = null): Vehicle
    fun getVehicleType(): VehicleType
    fun getDefaultDailyRate(): Double
    fun getDefaultFeatures(): Set<String>
}

/** Economy vehicle factory */
class EconomyVehicleFactory : VehicleFactory {
    
    override fun createVehicle(config: VehicleConfig?): Vehicle {
        return Vehicle(
            id = generateId(),
            make = config?.make ?: DEFAULT_MAKE,
            model = config?.model ?: DEFAULT_MODEL,
            year = config?.year ?: DEFAULT_YEAR,
            type = VehicleType.ECONOMY,
            dailyRate = config?.dailyRate ?: getDefaultDailyRate(),
            fuelType = config?.fuelType ?: FuelType.GASOLINE,
            passengerCapacity = config?.passengerCapacity ?: DEFAULT_CAPACITY,
            features = config?.features ?: getDefaultFeatures()
        )
    }
    
    override fun getVehicleType(): VehicleType = VehicleType.ECONOMY
    override fun getDefaultDailyRate(): Double = 35.0
    override fun getDefaultFeatures(): Set<String> = setOf("AC", "Radio", "USB Charging")
    
    companion object {
        private const val DEFAULT_MAKE = "Toyota"
        private const val DEFAULT_MODEL = "Yaris"
        private const val DEFAULT_YEAR = 2023
        private const val DEFAULT_CAPACITY = 4
    }
}

/** Compact vehicle factory */
class CompactVehicleFactory : VehicleFactory {
    
    override fun createVehicle(config: VehicleConfig?): Vehicle {
        return Vehicle(
            id = generateId(),
            make = config?.make ?: DEFAULT_MAKE,
            model = config?.model ?: DEFAULT_MODEL,
            year = config?.year ?: DEFAULT_YEAR,
            type = VehicleType.COMPACT,
            dailyRate = config?.dailyRate ?: getDefaultDailyRate(),
            fuelType = config?.fuelType ?: FuelType.GASOLINE,
            passengerCapacity = config?.passengerCapacity ?: DEFAULT_CAPACITY,
            features = config?.features ?: getDefaultFeatures()
        )
    }
    
    override fun getVehicleType(): VehicleType = VehicleType.COMPACT
    override fun getDefaultDailyRate(): Double = 45.0
    override fun getDefaultFeatures(): Set<String> = setOf("AC", "Radio", "USB Charging", "Bluetooth")
    
    companion object {
        private const val DEFAULT_MAKE = "Honda"
        private const val DEFAULT_MODEL = "Civic"
        private const val DEFAULT_YEAR = 2023
        private const val DEFAULT_CAPACITY = 5
    }
}

/** SUV vehicle factory */
class SUVVehicleFactory : VehicleFactory {
    
    override fun createVehicle(config: VehicleConfig?): Vehicle {
        return Vehicle(
            id = generateId(),
            make = config?.make ?: DEFAULT_MAKE,
            model = config?.model ?: DEFAULT_MODEL,
            year = config?.year ?: DEFAULT_YEAR,
            type = VehicleType.SUV,
            dailyRate = config?.dailyRate ?: getDefaultDailyRate(),
            fuelType = config?.fuelType ?: FuelType.GASOLINE,
            passengerCapacity = config?.passengerCapacity ?: DEFAULT_CAPACITY,
            features = config?.features ?: getDefaultFeatures()
        )
    }
    
    override fun getVehicleType(): VehicleType = VehicleType.SUV
    override fun getDefaultDailyRate(): Double = 75.0
    override fun getDefaultFeatures(): Set<String> = setOf(
        "AC", "Radio", "USB Charging", "Bluetooth", "Navigation",
        "4WD", "Roof Rails", "Third Row Seating"
    )
    
    companion object {
        private const val DEFAULT_MAKE = "Toyota"
        private const val DEFAULT_MODEL = "RAV4"
        private const val DEFAULT_YEAR = 2023
        private const val DEFAULT_CAPACITY = 7
    }
}

/** Luxury vehicle factory */
class LuxuryVehicleFactory : VehicleFactory {
    
    override fun createVehicle(config: VehicleConfig?): Vehicle {
        return Vehicle(
            id = generateId(),
            make = config?.make ?: DEFAULT_MAKE,
            model = config?.model ?: DEFAULT_MODEL,
            year = config?.year ?: DEFAULT_YEAR,
            type = VehicleType.LUXURY,
            dailyRate = config?.dailyRate ?: getDefaultDailyRate(),
            fuelType = config?.fuelType ?: FuelType.HYBRID,
            passengerCapacity = config?.passengerCapacity ?: DEFAULT_CAPACITY,
            features = config?.features ?: getDefaultFeatures()
        )
    }
    
    override fun getVehicleType(): VehicleType = VehicleType.LUXURY
    override fun getDefaultDailyRate(): Double = 150.0
    override fun getDefaultFeatures(): Set<String> = setOf(
        "AC", "Premium Sound System", "USB Charging", "Bluetooth", "Navigation",
        "Leather Seats", "Heated Seats", "Cooled Seats", "Sunroof",
        "360 Camera", "Parking Assist", "Lane Assist", "Adaptive Cruise Control"
    )
    
    companion object {
        private const val DEFAULT_MAKE = "BMW"
        private const val DEFAULT_MODEL = "5 Series"
        private const val DEFAULT_YEAR = 2024
        private const val DEFAULT_CAPACITY = 5
    }
}

/** Van vehicle factory */
class VanVehicleFactory : VehicleFactory {
    
    override fun createVehicle(config: VehicleConfig?): Vehicle {
        return Vehicle(
            id = generateId(),
            make = config?.make ?: DEFAULT_MAKE,
            model = config?.model ?: DEFAULT_MODEL,
            year = config?.year ?: DEFAULT_YEAR,
            type = VehicleType.VAN,
            dailyRate = config?.dailyRate ?: getDefaultDailyRate(),
            fuelType = config?.fuelType ?: FuelType.GASOLINE,
            passengerCapacity = config?.passengerCapacity ?: DEFAULT_CAPACITY,
            features = config?.features ?: getDefaultFeatures()
        )
    }
    
    override fun getVehicleType(): VehicleType = VehicleType.VAN
    override fun getDefaultDailyRate(): Double = 95.0
    override fun getDefaultFeatures(): Set<String> = setOf(
        "AC", "Radio", "USB Charging", "Bluetooth",
        "Sliding Doors", "Rear Entertainment", "Cargo Space"
    )
    
    companion object {
        private const val DEFAULT_MAKE = "Honda"
        private const val DEFAULT_MODEL = "Odyssey"
        private const val DEFAULT_YEAR = 2023
        private const val DEFAULT_CAPACITY = 8
    }
}

/** Motorcycle factory */
class MotorcycleVehicleFactory : VehicleFactory {
    
    override fun createVehicle(config: VehicleConfig?): Vehicle {
        return Vehicle(
            id = generateId(),
            make = config?.make ?: DEFAULT_MAKE,
            model = config?.model ?: DEFAULT_MODEL,
            year = config?.year ?: DEFAULT_YEAR,
            type = VehicleType.MOTORCYCLE,
            dailyRate = config?.dailyRate ?: getDefaultDailyRate(),
            fuelType = config?.fuelType ?: FuelType.GASOLINE,
            passengerCapacity = config?.passengerCapacity ?: DEFAULT_CAPACITY,
            features = config?.features ?: getDefaultFeatures()
        )
    }
    
    override fun getVehicleType(): VehicleType = VehicleType.MOTORCYCLE
    override fun getDefaultDailyRate(): Double = 55.0
    override fun getDefaultFeatures(): Set<String> = setOf("ABS", "Helmet Included", "Saddlebags")
    
    companion object {
        private const val DEFAULT_MAKE = "Harley-Davidson"
        private const val DEFAULT_MODEL = "Street 750"
        private const val DEFAULT_YEAR = 2023
        private const val DEFAULT_CAPACITY = 2
    }
}

/** Midsize vehicle factory */
class MidsizeVehicleFactory : VehicleFactory {
    
    override fun createVehicle(config: VehicleConfig?): Vehicle {
        return Vehicle(
            id = generateId(),
            make = config?.make ?: DEFAULT_MAKE,
            model = config?.model ?: DEFAULT_MODEL,
            year = config?.year ?: DEFAULT_YEAR,
            type = VehicleType.MIDSIZE,
            dailyRate = config?.dailyRate ?: getDefaultDailyRate(),
            fuelType = config?.fuelType ?: FuelType.GASOLINE,
            passengerCapacity = config?.passengerCapacity ?: DEFAULT_CAPACITY,
            features = config?.features ?: getDefaultFeatures()
        )
    }
    
    override fun getVehicleType(): VehicleType = VehicleType.MIDSIZE
    override fun getDefaultDailyRate(): Double = 55.0
    override fun getDefaultFeatures(): Set<String> = setOf(
        "AC", "Radio", "USB Charging", "Bluetooth", "Backup Camera"
    )
    
    companion object {
        private const val DEFAULT_MAKE = "Toyota"
        private const val DEFAULT_MODEL = "Camry"
        private const val DEFAULT_YEAR = 2023
        private const val DEFAULT_CAPACITY = 5
    }
}

/** Abstract factory for creating vehicle factories */
object VehicleFactoryProvider {
    
    private val factories = mapOf(
        VehicleType.ECONOMY to EconomyVehicleFactory(),
        VehicleType.COMPACT to CompactVehicleFactory(),
        VehicleType.MIDSIZE to MidsizeVehicleFactory(),
        VehicleType.SUV to SUVVehicleFactory(),
        VehicleType.LUXURY to LuxuryVehicleFactory(),
        VehicleType.VAN to VanVehicleFactory(),
        VehicleType.MOTORCYCLE to MotorcycleVehicleFactory()
    )
    
    fun getFactory(type: VehicleType): VehicleFactory {
        return factories[type] 
            ?: throw IllegalArgumentException("No factory for vehicle type: $type")
    }
    
    fun createVehicle(type: VehicleType, config: VehicleConfig? = null): Vehicle {
        return getFactory(type).createVehicle(config)
    }
    
    fun getAllFactories(): Map<VehicleType, VehicleFactory> = factories.toMap()
}

/** Fleet manager using factory pattern */
class FactoryFleetManager {
    private val vehicles = mutableMapOf<String, Vehicle>()
    private val locations = mutableMapOf<String, Location>()
    
    fun createVehicle(type: VehicleType, config: VehicleConfig? = null): Vehicle {
        val vehicle = VehicleFactoryProvider.createVehicle(type, config)
        vehicles[vehicle.id] = vehicle
        return vehicle
    }
    
    fun createVehicleWithConfig(config: VehicleConfig): Vehicle {
        val vehicle = VehicleFactoryProvider.createVehicle(config.type, config)
        vehicles[vehicle.id] = vehicle
        return vehicle
    }
    
    fun createFleet(type: VehicleType, count: Int): List<Vehicle> {
        return (1..count).map { createVehicle(type) }
    }
    
    fun createMixedFleet(distribution: Map<VehicleType, Int>): List<Vehicle> {
        return distribution.flatMap { (type, count) ->
            createFleet(type, count)
        }
    }
    
    fun addLocation(location: Location) {
        locations[location.id] = location
    }
    
    fun assignVehicleToLocation(vehicleId: String, locationId: String): Boolean {
        val vehicle = vehicles[vehicleId] ?: return false
        val location = locations[locationId] ?: return false
        
        locations.values.forEach { it.vehicles.remove(vehicle) }
        location.vehicles.add(vehicle)
        
        return true
    }
    
    fun getVehicle(vehicleId: String): Vehicle? = vehicles[vehicleId]
    fun getLocation(locationId: String): Location? = locations[locationId]
    
    fun getAllVehicles(): List<Vehicle> = vehicles.values.toList()
    fun getAllLocations(): List<Location> = locations.values.toList()
    
    fun getVehiclesByType(type: VehicleType): List<Vehicle> {
        return vehicles.values.filter { it.type == type }
    }
    
    fun getAvailableVehicles(): List<Vehicle> {
        return vehicles.values.filter { it.isAvailable() }
    }
    
    fun getAvailableVehiclesByType(type: VehicleType): List<Vehicle> {
        return vehicles.values.filter { it.isAvailable() && it.type == type }
    }
    
    fun getFleetStatistics(): FleetStatistics {
        val byType = VehicleType.entries.associateWith { type ->
            vehicles.values.count { it.type == type }
        }
        
        val byStatus = RentalStatus.entries.associateWith { status ->
            vehicles.values.count { it.status == status }
        }
        
        return FleetStatistics(
            totalVehicles = vehicles.size,
            availableVehicles = vehicles.values.count { it.isAvailable() },
            vehiclesByType = byType,
            vehiclesByStatus = byStatus,
            locationCount = locations.size
        )
    }
    
    fun getPricingTiers(): Map<VehicleType, Double> {
        return VehicleFactoryProvider.getAllFactories().mapValues { (_, factory) ->
            factory.getDefaultDailyRate()
        }
    }
}

/** Fleet statistics */
data class FleetStatistics(
    val totalVehicles: Int,
    val availableVehicles: Int,
    val vehiclesByType: Map<VehicleType, Int>,
    val vehiclesByStatus: Map<RentalStatus, Int>,
    val locationCount: Int
)

/** Electric vehicle factory - specialized factory */
class ElectricVehicleFactory(
    private val baseType: VehicleType = VehicleType.COMPACT
) : VehicleFactory {
    
    override fun createVehicle(config: VehicleConfig?): Vehicle {
        val baseFactory = VehicleFactoryProvider.getFactory(baseType)
        val baseVehicle = baseFactory.createVehicle(config)
        
        return baseVehicle.copy(
            id = generateId(),
            fuelType = FuelType.ELECTRIC,
            features = baseVehicle.features + setOf(
                "Electric Motor", "Regenerative Braking", 
                "Fast Charging Compatible", "Range Display"
            ),
            dailyRate = baseVehicle.dailyRate * ELECTRIC_PREMIUM_MULTIPLIER
        )
    }
    
    override fun getVehicleType(): VehicleType = baseType
    override fun getDefaultDailyRate(): Double = 
        VehicleFactoryProvider.getFactory(baseType).getDefaultDailyRate() * ELECTRIC_PREMIUM_MULTIPLIER
    override fun getDefaultFeatures(): Set<String> = 
        VehicleFactoryProvider.getFactory(baseType).getDefaultFeatures() + setOf(
            "Electric Motor", "Regenerative Braking", "Fast Charging Compatible"
        )
    
    companion object {
        private const val ELECTRIC_PREMIUM_MULTIPLIER = 1.2
    }
}

/** Fleet builder using fluent API */
class FleetBuilder {
    private val vehicleConfigs = mutableListOf<Pair<VehicleType, VehicleConfig?>>()
    private val locations = mutableListOf<Location>()
    
    fun addEconomy(count: Int = 1, config: VehicleConfig? = null): FleetBuilder {
        repeat(count) { vehicleConfigs.add(VehicleType.ECONOMY to config) }
        return this
    }
    
    fun addCompact(count: Int = 1, config: VehicleConfig? = null): FleetBuilder {
        repeat(count) { vehicleConfigs.add(VehicleType.COMPACT to config) }
        return this
    }
    
    fun addMidsize(count: Int = 1, config: VehicleConfig? = null): FleetBuilder {
        repeat(count) { vehicleConfigs.add(VehicleType.MIDSIZE to config) }
        return this
    }
    
    fun addSUV(count: Int = 1, config: VehicleConfig? = null): FleetBuilder {
        repeat(count) { vehicleConfigs.add(VehicleType.SUV to config) }
        return this
    }
    
    fun addLuxury(count: Int = 1, config: VehicleConfig? = null): FleetBuilder {
        repeat(count) { vehicleConfigs.add(VehicleType.LUXURY to config) }
        return this
    }
    
    fun addVan(count: Int = 1, config: VehicleConfig? = null): FleetBuilder {
        repeat(count) { vehicleConfigs.add(VehicleType.VAN to config) }
        return this
    }
    
    fun addMotorcycle(count: Int = 1, config: VehicleConfig? = null): FleetBuilder {
        repeat(count) { vehicleConfigs.add(VehicleType.MOTORCYCLE to config) }
        return this
    }
    
    fun addLocation(location: Location): FleetBuilder {
        locations.add(location)
        return this
    }
    
    fun build(): FactoryFleetManager {
        val manager = FactoryFleetManager()
        
        locations.forEach { manager.addLocation(it) }
        
        vehicleConfigs.forEach { (type, config) ->
            manager.createVehicle(type, config)
        }
        
        return manager
    }
}

private fun generateId(): String = "VEH-${UUID.randomUUID().toString().take(8).uppercase()}"
