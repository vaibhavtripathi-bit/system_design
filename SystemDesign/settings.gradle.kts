rootProject.name = "SystemDesign"

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// Networking & Data Layer
include(":image-loader")
include(":http-client")
include(":sync-engine")
include(":pagination-engine")

// Storage & Caching
include(":lru-cache")
include(":key-value-store")
include(":download-manager")

// Messaging & Events
include(":event-bus")
include(":push-notification")
include(":realtime-messaging")

// Concurrency & Scheduling
include(":task-scheduler")
include(":rate-limiter")
include(":background-task")

// Observability & Reliability
include(":analytics-pipeline")
include(":crash-reporter")
include(":feature-flag")

// App Architecture
include(":dependency-injection")
include(":navigation-manager")
include(":plugin-system")

// Security & Auth
include(":token-manager")
