package com.systemdesign.pushnotification

import com.systemdesign.pushnotification.approach_01_simple.*
import com.systemdesign.pushnotification.approach_02_queued.*
import com.systemdesign.pushnotification.approach_03_priority.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

class PushNotificationTest {

    // Simple Push Handler Tests
    @Test
    fun `simple - routes to correct handler`() {
        val handler = SimplePushHandler()
        val received = mutableListOf<PushNotification>()
        
        handler.registerHandler(TypedNotificationHandler("chat") { received.add(it) })
        
        handler.handleNotification(PushNotification("1", "chat", "Title", "Body"))
        
        assertEquals(1, received.size)
        assertEquals("chat", received[0].type)
    }

    @Test
    fun `simple - ignores unhandled types`() {
        val handler = SimplePushHandler()
        val received = mutableListOf<PushNotification>()
        
        handler.registerHandler(TypedNotificationHandler("chat") { received.add(it) })
        
        handler.handleNotification(PushNotification("1", "promo", "Title", "Body"))
        
        assertEquals(0, received.size)
    }

    @Test
    fun `simple - multiple handlers for different types`() {
        val handler = SimplePushHandler()
        val chatReceived = mutableListOf<PushNotification>()
        val promoReceived = mutableListOf<PushNotification>()
        
        handler.registerHandler(TypedNotificationHandler("chat") { chatReceived.add(it) })
        handler.registerHandler(TypedNotificationHandler("promo") { promoReceived.add(it) })
        
        handler.handleNotification(PushNotification("1", "chat", "Chat", "Message"))
        handler.handleNotification(PushNotification("2", "promo", "Sale", "50% off"))
        
        assertEquals(1, chatReceived.size)
        assertEquals(1, promoReceived.size)
    }

    // Queued Push Handler Tests
    @Test
    fun `queued - deduplicates notifications`() = runBlocking {
        val handler = QueuedPushHandler()
        val received = CopyOnWriteArrayList<PushNotification>()
        
        handler.registerHandler(TypedNotificationHandler("chat") { received.add(it) })
        handler.start()
        
        handler.enqueue(PushNotification("1", "chat", "Title", "Body"))
        handler.enqueue(PushNotification("1", "chat", "Title", "Body"))
        handler.enqueue(PushNotification("1", "chat", "Title", "Body"))
        
        delay(300)
        
        assertEquals(1, received.size)
        handler.shutdown()
    }

    @Test
    fun `queued - processes different notifications`() = runBlocking {
        val handler = QueuedPushHandler()
        val received = CopyOnWriteArrayList<PushNotification>()
        
        handler.registerHandler(TypedNotificationHandler("chat") { received.add(it) })
        handler.start()
        
        handler.enqueue(PushNotification("1", "chat", "Title1", "Body1"))
        handler.enqueue(PushNotification("2", "chat", "Title2", "Body2"))
        handler.enqueue(PushNotification("3", "chat", "Title3", "Body3"))
        
        delay(300)
        
        assertEquals(3, received.size)
        handler.shutdown()
    }

    // Priority Push Handler Tests
    @Test
    fun `priority - processes urgent first`() {
        val handler = PriorityPushHandler()
        val received = mutableListOf<PushNotification>()
        
        handler.registerHandler(TypedNotificationHandler("alert") { received.add(it) })
        
        handler.enqueue(PrioritizedNotification(
            PushNotification("1", "alert", "Low", "Low priority"),
            NotificationPriority.LOW
        ))
        handler.enqueue(PrioritizedNotification(
            PushNotification("2", "alert", "Urgent", "Urgent!"),
            NotificationPriority.URGENT
        ))
        handler.enqueue(PrioritizedNotification(
            PushNotification("3", "alert", "Normal", "Normal priority"),
            NotificationPriority.NORMAL
        ))
        
        handler.processAll()
        
        assertEquals(3, received.size)
        assertEquals("2", received[0].id)
        assertEquals("3", received[1].id)
        assertEquals("1", received[2].id)
    }

    @Test
    fun `priority - respects channel enabled state`() {
        val handler = PriorityPushHandler()
        val received = mutableListOf<PushNotification>()
        
        handler.registerHandler(TypedNotificationHandler("promo") { received.add(it) })
        handler.createChannel(NotificationChannel("promo", "Promotions", enabled = false))
        
        handler.enqueue(PrioritizedNotification(
            PushNotification("1", "promo", "Sale", "50% off"),
            NotificationPriority.NORMAL,
            "promo"
        ))
        
        handler.processAll()
        
        assertEquals(0, received.size)
    }

    @Test
    fun `priority - manages channels`() {
        val handler = PriorityPushHandler()
        
        handler.createChannel(NotificationChannel("chat", "Chat Messages"))
        handler.createChannel(NotificationChannel("alerts", "Alerts", importance = NotificationPriority.HIGH))
        
        val channels = handler.getChannels()
        
        assertEquals(3, channels.size)
        assertTrue(handler.isChannelEnabled("chat"))
    }

    @Test
    fun `priority - tracks pending count`() {
        val handler = PriorityPushHandler()
        
        handler.enqueue(PrioritizedNotification(
            PushNotification("1", "alert", "Test", "Test"),
            NotificationPriority.NORMAL
        ))
        handler.enqueue(PrioritizedNotification(
            PushNotification("2", "alert", "Test", "Test"),
            NotificationPriority.HIGH
        ))
        
        assertEquals(2, handler.getPendingCount())
        
        handler.processNext()
        
        assertEquals(1, handler.getPendingCount())
    }
}
