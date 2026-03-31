# System Design

A comprehensive collection of system design patterns and implementations in Kotlin, covering both infrastructure building blocks and classic OO design problems. Each module provides multiple approaches using different design patterns, with full test coverage.

## Project Structure

This repository contains two independent Gradle projects and supplementary documentation:

```
system_design/
├── SystemDesign/                        # Infrastructure & app building blocks (Kotlin)
├── genericSystemDesign/                 # Classic OO / domain design problems (Kotlin)
├── android_system_design_l6.html        # Android system design interview notes
├── android_oo_system_design_l6.html     # Android OO design interview notes
├── LICENSE                              # MIT License
└── README.md
```

## SystemDesign — Infrastructure Building Blocks

Reusable infrastructure components, each implemented with 3+ design pattern approaches.

| Category | Module | Description |
|----------|--------|-------------|
| **Networking & Data** | `image-loader` | Image loading with caching and transformations |
| | `http-client` | HTTP client with interceptors and retry |
| | `sync-engine` | Data synchronization (CRDT, operational transform) |
| | `pagination-engine` | Cursor and offset-based pagination |
| **Storage & Caching** | `lru-cache` | LRU cache with eviction policies |
| | `key-value-store` | In-memory, persistent, and sharded KV stores |
| | `download-manager` | Simple, concurrent, and resumable downloads |
| **Messaging & Events** | `event-bus` | Publish-subscribe event system |
| | `push-notification` | Push notification delivery |
| | `realtime-messaging` | Polling, WebSocket, and pub/sub messaging |
| **Concurrency & Scheduling** | `task-scheduler` | Task scheduling with priorities |
| | `rate-limiter` | Token bucket, leaky bucket, sliding/fixed window |
| | `background-task` | Background job processing |
| **Observability & Reliability** | `analytics-pipeline` | Event tracking and analytics |
| | `crash-reporter` | Crash reporting and logging |
| | `feature-flag` | Feature toggle system |
| **Resilience & Pooling** | `circuit-breaker` | Count-based, time-based, and adaptive circuit breakers |
| | `connection-pool` | Blocking, coroutine-based, and adaptive connection pools |
| **App Architecture** | `dependency-injection` | DI framework implementation |
| | `navigation-manager` | Navigation with back stack management |
| | `plugin-system` | Extensible plugin architecture |
| **Security & Auth** | `token-manager` | Token lifecycle and refresh management |

## genericSystemDesign — Classic Design Problems

Object-oriented design problems commonly asked in interviews, each with multiple pattern-based solutions.

| Category | Module | Approaches |
|----------|--------|------------|
| **Hardware / Embedded** | `elevator-system` | State machine, strategy dispatch, command pattern |
| | `atm-machine` | State machine, chain of responsibility, template method |
| | `traffic-light` | State machine, strategy timing, observer coordination |
| | `vending-machine` | State machine, strategy, command |
| **Games** | `chess-game` | Strategy movement, command/memento, observer events |
| | `board-game` | State machine, strategy, observer |
| | `card-game` | State machine, strategy, observer |
| **Booking & Reservation** | `movie-booking` | Strategy pricing, state machine, observer |
| | `hotel-reservation` | Strategy, state machine, observer |
| | `flight-booking` | Strategy, state machine, observer |
| | `meeting-scheduler` | Interval tree, strategy scheduling, observer calendar |
| **E-Commerce** | `shopping-cart` | Decorator discounts, state machine orders, strategy pricing |
| | `food-delivery` | State machine, strategy, observer |
| **Communication** | `chat-system` | Observer real-time, mediator rooms, command messaging |
| | `notification-system` | Strategy, observer, command |
| **Transportation** | `ride-sharing` | Strategy, state machine, observer |
| | `vehicle-rental` | Strategy, state machine, observer |
| **Finance** | `expense-sharing` | Graph simplification, mediator settlement, event sourcing |
| | `expense-tracker` | Strategy split, observer balances, command transactions |
| **Management** | `library-management` | Strategy, state machine, observer |
| | `social-media-feed` | Strategy, observer, command |
| | `url-shortener` | Strategy, decorator, observer |

## Tech Stack

- **Language:** Kotlin 1.9.22
- **Build System:** Gradle with Kotlin DSL
- **JVM Target:** Java 17
- **Testing:** JUnit 5, MockK, Kotlinx Coroutines Test
- **Concurrency:** Kotlinx Coroutines 1.8.0

## Getting Started

### Prerequisites

- JDK 17+
- Gradle 8+ (or use the included Gradle wrapper)

### Build & Test

Each Gradle project is independent. Navigate to the project directory and use the wrapper:

```bash
# SystemDesign modules
cd SystemDesign
./gradlew build
./gradlew test

# genericSystemDesign modules
cd genericSystemDesign
./gradlew build
./gradlew test

# Run tests for a specific module
cd SystemDesign
./gradlew :circuit-breaker:test
./gradlew :rate-limiter:test

cd genericSystemDesign
./gradlew :chess-game:test
./gradlew :chat-system:test
```

### Module Structure

Each module follows a consistent structure:

```
module-name/
├── build.gradle.kts
└── src/
    ├── main/kotlin/com/systemdesign/<module>/
    │   ├── common/                    # Shared models and interfaces
    │   ├── approach_01_<pattern>/     # First design pattern approach
    │   ├── approach_02_<pattern>/     # Second design pattern approach
    │   └── approach_03_<pattern>/     # Third design pattern approach
    └── test/kotlin/com/systemdesign/<module>/
        └── ModuleTest.kt             # Comprehensive test suite
```

## Use Cases

- Interview preparation for system design and OO design rounds
- Reference implementations for common design patterns
- Learning scalable system architecture
- Comparing trade-offs between different design approaches

## License

[MIT License](LICENSE)
