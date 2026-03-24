# System Design

A comprehensive collection of system design patterns, implementations, and documentation for building scalable systems.

## Contents

### Android System Design

- `android_system_design_l6.html` - Android-specific system design topics for senior-level interviews
- `android_oo_system_design_l6.html` - Object-oriented design patterns for Android

### Generic System Design

Common system design components and patterns implemented in Kotlin:

| Component | Description |
|-----------|-------------|
| **Analytics Pipeline** | Event tracking and analytics system |
| **Background Task** | Background job processing |
| **Crash Reporter** | Crash reporting and logging |
| **Dependency Injection** | DI framework implementation |
| **Download Manager** | File download management system |
| **Event Bus** | Publish-subscribe event system |
| **Feature Flag** | Feature toggle system |
| **HTTP Client** | Network client implementation |
| **Image Loader** | Efficient image loading and caching |
| **Key-Value Store** | Persistent key-value storage |
| **LRU Cache** | Least Recently Used cache implementation |

### System Design Components

Located in `SystemDesign/` folder with implementations for:
- Caching strategies
- Network layers
- Data persistence
- Background processing
- Event-driven architectures

## Project Structure

```
system_design/
├── SystemDesign/                    # Kotlin implementations
│   ├── analytics-pipeline/
│   ├── background-task/
│   ├── crash-reporter/
│   ├── dependency-injection/
│   ├── download-manager/
│   ├── event-bus/
│   ├── feature-flag/
│   ├── http-client/
│   ├── image-loader/
│   ├── key-value-store/
│   ├── lru-cache/
│   └── ...
├── genericSystemDesign/             # Generic system design docs
├── android_system_design_l6.html    # Android system design
└── android_oo_system_design_l6.html # OO design for Android
```

## Tech Stack

- **Language**: Kotlin
- **Build System**: Gradle with Kotlin DSL
- **Documentation**: HTML

## How to Use

1. Browse the `SystemDesign/` folder for Kotlin implementations
2. Open HTML files in a browser for documentation
3. Each component folder contains self-contained implementations

## Use Cases

- Interview preparation for system design rounds
- Reference implementations for common patterns
- Learning scalable system architecture
- Android-specific design patterns

## License

MIT License
