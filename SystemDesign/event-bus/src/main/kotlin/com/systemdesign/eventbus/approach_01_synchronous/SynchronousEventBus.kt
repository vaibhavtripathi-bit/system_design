/**
 * # Approach 01: Synchronous Event Bus
 *
 * ## Pattern Used
 * Simple pub/sub with synchronous delivery. Handlers execute in order on caller's thread.
 *
 * ## Trade-offs
 * - **Pros:** Simple, predictable order, easy debugging
 * - **Cons:** Slow handlers block publisher, no isolation
 *
 * ## When to Prefer
 * - Simple applications
 * - When order matters
 * - Testing
 */
package com.systemdesign.eventbus.approach_01_synchronous

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass

interface Event

interface EventHandler<T : Event> {
    fun handle(event: T)
}

class SynchronousEventBus {
    private val handlers = ConcurrentHashMap<KClass<*>, CopyOnWriteArrayList<EventHandler<*>>>()

    inline fun <reified T : Event> subscribe(handler: EventHandler<T>) {
        subscribe(T::class, handler)
    }

    fun <T : Event> subscribe(eventClass: KClass<T>, handler: EventHandler<T>) {
        handlers.getOrPut(eventClass) { CopyOnWriteArrayList() }.add(handler)
    }

    inline fun <reified T : Event> unsubscribe(handler: EventHandler<T>) {
        unsubscribe(T::class, handler)
    }

    fun <T : Event> unsubscribe(eventClass: KClass<T>, handler: EventHandler<T>) {
        handlers[eventClass]?.remove(handler)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Event> publish(event: T) {
        val eventHandlers = handlers[event::class] as? List<EventHandler<T>> ?: return
        eventHandlers.forEach { it.handle(event) }
    }

    fun clear() = handlers.clear()
}

inline fun <reified T : Event> SynchronousEventBus.on(crossinline block: (T) -> Unit): EventHandler<T> {
    val handler = object : EventHandler<T> {
        override fun handle(event: T) = block(event)
    }
    subscribe(handler)
    return handler
}
