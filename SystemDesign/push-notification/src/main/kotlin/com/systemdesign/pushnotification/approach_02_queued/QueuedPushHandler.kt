/**
 * # Approach 02: Queued Push Notification Handler
 *
 * ## Pattern Used
 * Queue-based processing with deduplication and batching.
 *
 * ## Trade-offs
 * - **Pros:** Prevents duplicates, ordered processing, batching
 * - **Cons:** Slight delay, more complex
 */
package com.systemdesign.pushnotification.approach_02_queued

import com.systemdesign.pushnotification.approach_01_simple.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap

class QueuedPushHandler(
    private val batchSize: Int = 10,
    private val batchDelayMs: Long = 100,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val queue = Channel<PushNotification>(Channel.UNLIMITED)
    private val seenIds = ConcurrentHashMap.newKeySet<String>()
    private val handlers = mutableListOf<NotificationHandler>()
    private var processingJob: Job? = null

    fun start() {
        processingJob = scope.launch {
            while (isActive) {
                val batch = mutableListOf<PushNotification>()
                
                repeat(batchSize) {
                    val notification = queue.tryReceive().getOrNull() ?: return@repeat
                    if (seenIds.add(notification.id)) {
                        batch.add(notification)
                    }
                }
                
                if (batch.isNotEmpty()) {
                    batch.forEach { notification ->
                        handlers.find { it.canHandle(notification.type) }?.handle(notification)
                    }
                }
                
                delay(batchDelayMs)
            }
        }
    }

    fun enqueue(notification: PushNotification) {
        queue.trySend(notification)
    }

    fun registerHandler(handler: NotificationHandler) {
        handlers.add(handler)
    }

    fun clearSeenIds() = seenIds.clear()

    fun shutdown() {
        processingJob?.cancel()
        scope.cancel()
    }
}
