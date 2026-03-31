package com.systemdesign.realtimemessaging

import com.systemdesign.realtimemessaging.approach_01_polling.*
import com.systemdesign.realtimemessaging.approach_02_websocket.*
import com.systemdesign.realtimemessaging.approach_03_pubsub.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

class RealtimeMessagingTest {

    // Polling Tests
    @Test
    fun `polling - creates subscription`() = runBlocking {
        val source = object : MessageSource {
            override suspend fun fetchMessages(channelId: String, since: Long): List<Message> {
                return emptyList()
            }
        }
        
        val client = PollingMessagingClient(source, pollIntervalMs = 1000)
        
        val flow = client.subscribe("channel1")
        
        assertNotNull(flow)
        client.shutdown()
    }

    @Test
    fun `polling - filters by channel`() = runBlocking {
        val messages = listOf(
            Message("1", "channel1", "user1", "Channel 1"),
            Message("2", "channel2", "user1", "Channel 2")
        )
        
        val source = object : MessageSource {
            var called = false
            override suspend fun fetchMessages(channelId: String, since: Long): List<Message> {
                return if (!called && channelId == "channel1") {
                    called = true
                    messages.filter { it.channelId == channelId }
                } else emptyList()
            }
        }
        
        val client = PollingMessagingClient(source, pollIntervalMs = 50)
        val received = CopyOnWriteArrayList<Message>()
        
        val job = launch {
            client.subscribe("channel1").take(1).collect { received.add(it) }
        }
        
        delay(200)
        job.cancelAndJoin()
        
        assertTrue(received.all { it.channelId == "channel1" })
        client.shutdown()
    }

    // Pub/Sub Tests
    @Test
    fun `pubsub - publishes and receives`() = runBlocking {
        val broker = PubSubMessagingBroker()
        val client = PubSubMessagingClient(broker, "client1")
        val received = CopyOnWriteArrayList<Message>()
        
        val job = launch {
            client.subscribe("topic1").take(2).collect { received.add(it) }
        }
        
        yield()
        client.publish("topic1", "Hello")
        client.publish("topic1", "World")
        
        delay(100)
        job.cancelAndJoin()
        
        assertEquals(2, received.size)
        client.shutdown()
    }

    @Test
    fun `pubsub - multiple subscribers`() = runBlocking {
        val broker = PubSubMessagingBroker()
        val client1 = PubSubMessagingClient(broker, "client1")
        val client2 = PubSubMessagingClient(broker, "client2")
        
        val received1 = CopyOnWriteArrayList<Message>()
        val received2 = CopyOnWriteArrayList<Message>()
        
        val job1 = launch {
            client1.subscribe("topic1").take(1).collect { received1.add(it) }
        }
        val job2 = launch {
            client2.subscribe("topic1").take(1).collect { received2.add(it) }
        }
        
        yield()
        client1.publish("topic1", "Broadcast")
        
        delay(100)
        job1.cancelAndJoin()
        job2.cancelAndJoin()
        
        assertEquals(1, received1.size)
        assertEquals(1, received2.size)
        
        client1.shutdown()
        client2.shutdown()
    }

    @Test
    fun `pubsub - topic isolation`() = runBlocking {
        val broker = PubSubMessagingBroker()
        val client = PubSubMessagingClient(broker, "client1")
        val received = CopyOnWriteArrayList<Message>()
        
        val job = launch {
            client.subscribe("topic1").take(1).collect { received.add(it) }
        }
        
        yield()
        client.publish("topic2", "Wrong topic")
        client.publish("topic1", "Right topic")
        
        delay(100)
        job.cancelAndJoin()
        
        assertEquals(1, received.size)
        assertEquals("Right topic", received[0].content)
        
        client.shutdown()
    }

    @Test
    fun `pubsub - broker manages topics`() {
        val broker = PubSubMessagingBroker()
        
        broker.createTopic("topic1")
        broker.createTopic("topic2")
        
        assertEquals(2, broker.getTopics().size)
        
        broker.deleteTopic("topic1")
        
        assertEquals(1, broker.getTopics().size)
        assertFalse(broker.getTopics().contains("topic1"))
    }

    @Test
    fun `pubsub - unsubscribe stops receiving`() = runBlocking {
        val broker = PubSubMessagingBroker()
        val client = PubSubMessagingClient(broker, "client1")
        val received = CopyOnWriteArrayList<Message>()
        
        val job = launch {
            client.messages.collect { received.add(it) }
        }
        
        client.subscribe("topic1")
        yield()
        
        client.publish("topic1", "First")
        delay(50)
        
        client.unsubscribe("topic1")
        delay(50)
        
        client.publish("topic1", "Second")
        delay(50)
        
        job.cancel()
        
        assertEquals(1, received.size)
        client.shutdown()
    }

    // WebSocket Tests
    class MockWebSocketConnection : WebSocketConnection {
        var connected = false
        val sentMessages = CopyOnWriteArrayList<String>()
        private var messageHandler: ((String) -> Unit)? = null
        private var disconnectHandler: (() -> Unit)? = null

        override suspend fun connect(url: String) { connected = true }
        override suspend fun send(message: String) { sentMessages.add(message) }
        override suspend fun disconnect() {
            connected = false
            disconnectHandler?.invoke()
        }
        override fun onMessage(handler: (String) -> Unit) { messageHandler = handler }
        override fun onDisconnect(handler: () -> Unit) { disconnectHandler = handler }

        fun simulateMessage(raw: String) { messageHandler?.invoke(raw) }
    }

    @Test
    fun `websocket - connect changes state to CONNECTED`() = runBlocking {
        val conn = MockWebSocketConnection()
        val client = WebSocketMessagingClient(conn, "ws://test", dispatcher = Dispatchers.Unconfined)

        assertEquals(ConnectionState.DISCONNECTED, client.state.value)
        client.connect()
        assertEquals(ConnectionState.CONNECTED, client.state.value)
        client.shutdown()
    }

    @Test
    fun `websocket - subscribe sends subscribe message`() = runBlocking {
        val conn = MockWebSocketConnection()
        val client = WebSocketMessagingClient(conn, "ws://test", dispatcher = Dispatchers.Unconfined)

        client.connect()
        client.subscribe("channel1")

        assertTrue(conn.sentMessages.any { it.contains("subscribe") && it.contains("channel1") })
        client.shutdown()
    }

    @Test
    fun `websocket - messages are filtered by channel`() = runBlocking {
        val conn = MockWebSocketConnection()
        val client = WebSocketMessagingClient(conn, "ws://test", dispatcher = Dispatchers.Unconfined)

        client.connect()
        val received = CopyOnWriteArrayList<Message>()

        val job = launch(Dispatchers.Unconfined) {
            client.subscribe("ch1").take(1).collect { received.add(it) }
        }

        conn.simulateMessage("1|ch1|user1|Hello")
        conn.simulateMessage("2|ch2|user1|Wrong")

        delay(50)
        job.cancelAndJoin()

        assertEquals(1, received.size)
        assertEquals("ch1", received[0].channelId)
        client.shutdown()
    }

    @Test
    fun `websocket - disconnect changes state`() = runBlocking {
        val conn = MockWebSocketConnection()
        val client = WebSocketMessagingClient(conn, "ws://test", dispatcher = Dispatchers.Unconfined)

        client.connect()
        assertEquals(ConnectionState.CONNECTED, client.state.value)

        client.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, client.state.value)
        client.shutdown()
    }

    @Test
    fun `websocket - send throws when not connected`() = runBlocking {
        val conn = MockWebSocketConnection()
        val client = WebSocketMessagingClient(conn, "ws://test", dispatcher = Dispatchers.Unconfined)

        assertThrows(IllegalStateException::class.java) {
            runBlocking { client.send("ch1", "Hello") }
        }
        client.shutdown()
    }
}
