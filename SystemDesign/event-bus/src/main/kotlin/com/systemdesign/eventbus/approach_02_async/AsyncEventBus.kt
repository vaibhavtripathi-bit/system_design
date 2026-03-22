/**
 * # Approach 02: Asynchronous Event Bus
 *
 * ## Pattern Used
 * Coroutine-based async delivery with configurable dispatcher.
 *
 * ## Trade-offs
 * - **Pros:** Non-blocking, handlers isolated, better performance
 * - **Cons:** Order not guaranteed, harder to debug
 *
 * ## When to Prefer
 * - High-throughput scenarios
 * - When handler isolation is important
 */
package com.systemdesign.eventbus.approach_02_async

import com.systemdesign.eventbus.approach_01_synchronous.Event
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass

interface AsyncEventHandler<T : Event> {
    suspend fun handle(event: T)
}

class AsyncEventBus(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val errorHandler: (Throwable) -> Unit = { it.printStackTrace() }
) {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val handlers = ConcurrentHashMap<KClass<*>, CopyOnWriteArrayList<AsyncEventHandler<*>>>()

    inline fun <reified T : Event> subscribe(handler: AsyncEventHandler<T>) {
        subscribe(T::class, handler)
    }

    fun <T : Event> subscribe(eventClass: KClass<T>, handler: AsyncEventHandler<T>) {
        handlers.getOrPut(eventClass) { CopyOnWriteArrayList() }.add(handler)
    }

    inline fun <reified T : Event> unsubscribe(handler: AsyncEventHandler<T>) {
        unsubscribe(T::class, handler)
    }

    fun <T : Event> unsubscribe(eventClass: KClass<T>, handler: AsyncEventHandler<T>) {
        handlers[eventClass]?.remove(handler)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Event> publish(event: T) {
        val eventHandlers = handlers[event::class] as? List<AsyncEventHandler<T>> ?: return
        eventHandlers.forEach { handler ->
            scope.launch {
                try {
                    handler.handle(event)
                } catch (e: Exception) {
                    errorHandler(e)
                }
            }
        }
    }

    suspend fun <T : Event> publishAndWait(event: T) {
        val eventHandlers = handlers[event::class] as? List<AsyncEventHandler<T>> ?: return
        coroutineScope {
            eventHandlers.map { handler ->
                async {
                    try {
                        handler.handle(event)
                    } catch (e: Exception) {
                        errorHandler(e)
                    }
                }
            }.awaitAll()
        }
    }

    fun shutdown() {
        scope.cancel()
        handlers.clear()
    }
}

inline fun <reified T : Event> AsyncEventBus.on(crossinline block: suspend (T) -> Unit): AsyncEventHandler<T> {
    val handler = object : AsyncEventHandler<T> {
        override suspend fun handle(event: T) = block(event)
    }
    subscribe(handler)
    return handler
}
