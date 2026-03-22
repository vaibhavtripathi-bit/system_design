package com.systemdesign.eventbus

import com.systemdesign.eventbus.approach_01_synchronous.*
import com.systemdesign.eventbus.approach_02_async.*
import com.systemdesign.eventbus.approach_03_flow.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class UserCreated(val userId: String) : Event
data class UserDeleted(val userId: String) : Event
data class OrderPlaced(val orderId: String) : Event

class EventBusTest {

    // Synchronous Event Bus Tests
    @Test
    fun `sync - delivers events to handlers`() {
        val bus = SynchronousEventBus()
        val received = mutableListOf<Event>()
        
        bus.on<UserCreated> { received.add(it) }
        bus.publish(UserCreated("user1"))
        
        assertEquals(1, received.size)
        assertEquals("user1", (received[0] as UserCreated).userId)
    }

    @Test
    fun `sync - delivers to multiple handlers`() {
        val bus = SynchronousEventBus()
        val received1 = mutableListOf<Event>()
        val received2 = mutableListOf<Event>()
        
        bus.on<UserCreated> { received1.add(it) }
        bus.on<UserCreated> { received2.add(it) }
        bus.publish(UserCreated("user1"))
        
        assertEquals(1, received1.size)
        assertEquals(1, received2.size)
    }

    @Test
    fun `sync - filters by event type`() {
        val bus = SynchronousEventBus()
        val userEvents = mutableListOf<Event>()
        val orderEvents = mutableListOf<Event>()
        
        bus.on<UserCreated> { userEvents.add(it) }
        bus.on<OrderPlaced> { orderEvents.add(it) }
        
        bus.publish(UserCreated("user1"))
        bus.publish(OrderPlaced("order1"))
        
        assertEquals(1, userEvents.size)
        assertEquals(1, orderEvents.size)
    }

    @Test
    fun `sync - unsubscribe removes handler`() {
        val bus = SynchronousEventBus()
        val received = mutableListOf<Event>()
        
        val handler = bus.on<UserCreated> { received.add(it) }
        bus.publish(UserCreated("user1"))
        
        bus.unsubscribe(handler)
        bus.publish(UserCreated("user2"))
        
        assertEquals(1, received.size)
    }

    // Async Event Bus Tests
    @Test
    fun `async - delivers events asynchronously`() = runBlocking {
        val bus = AsyncEventBus()
        val received = CopyOnWriteArrayList<Event>()
        val latch = CountDownLatch(1)
        
        bus.on<UserCreated> { 
            received.add(it)
            latch.countDown()
        }
        bus.publish(UserCreated("user1"))
        
        latch.await(1, TimeUnit.SECONDS)
        assertEquals(1, received.size)
        
        bus.shutdown()
    }

    @Test
    fun `async - publishAndWait waits for handlers`() = runBlocking {
        val bus = AsyncEventBus()
        val received = CopyOnWriteArrayList<Event>()
        
        bus.on<UserCreated> { 
            delay(50)
            received.add(it)
        }
        
        bus.publishAndWait(UserCreated("user1"))
        
        assertEquals(1, received.size)
        bus.shutdown()
    }

    @Test
    fun `async - handles errors gracefully`() = runBlocking {
        var errorCaught = false
        val bus = AsyncEventBus(errorHandler = { errorCaught = true })
        
        bus.on<UserCreated> { throw RuntimeException("Test error") }
        bus.publishAndWait(UserCreated("user1"))
        
        assertTrue(errorCaught)
        bus.shutdown()
    }

    // Flow Event Bus Tests
    @Test
    fun `flow - publishes and receives events`() = runBlocking {
        val bus = FlowEventBus()
        
        val job = launch {
            val event = bus.subscribe<UserCreated>().first()
            assertEquals("user1", event.userId)
        }
        
        yield()
        bus.publish(UserCreated("user1"))
        job.join()
    }

    @Test
    fun `flow - filters by type`() = runBlocking {
        val bus = FlowEventBus()
        val events = mutableListOf<Event>()
        
        val job = launch {
            bus.subscribe<UserCreated>().take(2).toList().let { events.addAll(it) }
        }
        
        yield()
        bus.publish(OrderPlaced("order1"))
        bus.publish(UserCreated("user1"))
        bus.publish(OrderPlaced("order2"))
        bus.publish(UserCreated("user2"))
        
        job.join()
        
        assertEquals(2, events.size)
        assertTrue(events.all { it is UserCreated })
    }

    @Test
    fun `flow - tryPublish returns result`() = runBlocking {
        val bus = FlowEventBus()
        
        val result = bus.tryPublish(UserCreated("user1"))
        
        assertTrue(result)
    }

    // Sticky Event Bus Tests
    @Test
    fun `sticky - replays last event on subscribe`() = runBlocking {
        val bus = StickyEventBus()
        
        bus.publish(UserCreated("user1"))
        
        val event = bus.subscribe<UserCreated>().first()
        
        assertEquals("user1", event.userId)
    }

    @Test
    fun `sticky - getLastEvent returns cached event`() = runBlocking {
        val bus = StickyEventBus()
        
        bus.publish(UserCreated("user1"))
        
        val lastEvent = bus.getLastEvent<UserCreated>()
        
        assertNotNull(lastEvent)
        assertEquals("user1", lastEvent?.userId)
    }
}
