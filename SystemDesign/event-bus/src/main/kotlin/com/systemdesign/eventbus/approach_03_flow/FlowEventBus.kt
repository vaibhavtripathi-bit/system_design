/**
 * # Approach 03: Flow-Based Event Bus
 *
 * ## Pattern Used
 * SharedFlow-based reactive event stream with backpressure handling.
 *
 * ## Trade-offs
 * - **Pros:** Backpressure, replay, structured concurrency, testable
 * - **Cons:** More complex, requires coroutine knowledge
 *
 * ## When to Prefer
 * - Reactive applications
 * - When backpressure matters
 * - Complex event processing
 */
package com.systemdesign.eventbus.approach_03_flow

import com.systemdesign.eventbus.approach_01_synchronous.Event
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.reflect.KClass

class FlowEventBus(
    private val replay: Int = 0,
    private val extraBufferCapacity: Int = 64
) {
    private val _events = MutableSharedFlow<Event>(
        replay = replay,
        extraBufferCapacity = extraBufferCapacity
    )
    
    val events: SharedFlow<Event> = _events.asSharedFlow()

    suspend fun publish(event: Event) {
        _events.emit(event)
    }

    fun tryPublish(event: Event): Boolean = _events.tryEmit(event)

    inline fun <reified T : Event> subscribe(): Flow<T> = events.filterIsInstance<T>()

    fun <T : Event> subscribe(eventClass: KClass<T>): Flow<T> {
        @Suppress("UNCHECKED_CAST")
        return events.filter { eventClass.isInstance(it) } as Flow<T>
    }
}

class TypedFlowEventBus<T : Event>(
    replay: Int = 0,
    extraBufferCapacity: Int = 64
) {
    private val _events = MutableSharedFlow<T>(
        replay = replay,
        extraBufferCapacity = extraBufferCapacity
    )
    
    val events: SharedFlow<T> = _events.asSharedFlow()

    suspend fun publish(event: T) {
        _events.emit(event)
    }

    fun tryPublish(event: T): Boolean = _events.tryEmit(event)

    fun subscribe(): Flow<T> = events
}

class StickyEventBus(
    extraBufferCapacity: Int = 64
) {
    @PublishedApi
    internal val _events = MutableSharedFlow<Event>(
        replay = 1,
        extraBufferCapacity = extraBufferCapacity
    )
    
    @PublishedApi
    internal val lastEvents = mutableMapOf<KClass<*>, Event>()

    suspend fun publish(event: Event) {
        lastEvents[event::class] = event
        _events.emit(event)
    }

    inline fun <reified T : Event> subscribe(): Flow<T> {
        return _events.filterIsInstance<T>().onStart {
            lastEvents[T::class]?.let { emit(it as T) }
        }
    }

    inline fun <reified T : Event> getLastEvent(): T? {
        @Suppress("UNCHECKED_CAST")
        return lastEvents[T::class] as? T
    }

    fun clear() = lastEvents.clear()
}
