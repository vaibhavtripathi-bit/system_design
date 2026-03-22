/**
 * # Approach 03: Priority-Based Push Notification Handler
 *
 * ## Pattern Used
 * Priority queue with channel support and grouping.
 *
 * ## Trade-offs
 * - **Pros:** Important notifications first, channel management
 * - **Cons:** More complex, needs priority tuning
 */
package com.systemdesign.pushnotification.approach_03_priority

import com.systemdesign.pushnotification.approach_01_simple.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class NotificationPriority(val value: Int) {
    LOW(0), NORMAL(1), HIGH(2), URGENT(3)
}

data class PrioritizedNotification(
    val notification: PushNotification,
    val priority: NotificationPriority,
    val channel: String = "default"
)

data class NotificationChannel(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val importance: NotificationPriority = NotificationPriority.NORMAL
)

class PriorityPushHandler(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val lock = ReentrantLock()
    private val queue = PriorityQueue<PrioritizedNotification>(
        compareByDescending { it.priority.value }
    )
    private val channels = ConcurrentHashMap<String, NotificationChannel>()
    private val handlers = mutableListOf<NotificationHandler>()
    
    private val _notifications = MutableSharedFlow<PushNotification>(extraBufferCapacity = 64)
    val notifications: SharedFlow<PushNotification> = _notifications

    init {
        channels["default"] = NotificationChannel("default", "Default")
    }

    fun createChannel(channel: NotificationChannel) {
        channels[channel.id] = channel
    }

    fun deleteChannel(channelId: String) {
        channels.remove(channelId)
    }

    fun isChannelEnabled(channelId: String): Boolean {
        return channels[channelId]?.enabled ?: false
    }

    fun enqueue(notification: PrioritizedNotification) {
        val channel = channels[notification.channel]
        if (channel?.enabled != true) return
        
        lock.withLock {
            queue.offer(notification)
        }
    }

    fun processNext(): PushNotification? {
        val prioritized = lock.withLock { queue.poll() } ?: return null
        val notification = prioritized.notification
        
        handlers.find { it.canHandle(notification.type) }?.handle(notification)
        _notifications.tryEmit(notification)
        
        return notification
    }

    fun processAll() {
        while (true) {
            processNext() ?: break
        }
    }

    fun registerHandler(handler: NotificationHandler) {
        handlers.add(handler)
    }

    fun getPendingCount(): Int = lock.withLock { queue.size }

    fun getChannels(): List<NotificationChannel> = channels.values.toList()

    fun shutdown() {
        scope.cancel()
    }
}
