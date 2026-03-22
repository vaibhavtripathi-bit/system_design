/**
 * # Approach 01: Polling-Based Messaging
 *
 * ## Pattern Used
 * Periodic polling for new messages.
 *
 * ## Trade-offs
 * - **Pros:** Simple, works through firewalls, no persistent connection
 * - **Cons:** Latency, battery drain, unnecessary requests
 */
package com.systemdesign.realtimemessaging.approach_01_polling

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap

data class Message(
    val id: String,
    val channelId: String,
    val senderId: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

interface MessageSource {
    suspend fun fetchMessages(channelId: String, since: Long): List<Message>
}

class PollingMessagingClient(
    private val source: MessageSource,
    private val pollIntervalMs: Long = 3000,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val subscriptions = ConcurrentHashMap<String, Job>()
    private val lastTimestamps = ConcurrentHashMap<String, Long>()
    
    private val _messages = MutableSharedFlow<Message>(extraBufferCapacity = 64)
    val messages: SharedFlow<Message> = _messages

    fun subscribe(channelId: String): Flow<Message> {
        if (!subscriptions.containsKey(channelId)) {
            lastTimestamps[channelId] = System.currentTimeMillis()
            
            val job = scope.launch {
                while (isActive) {
                    try {
                        val since = lastTimestamps[channelId] ?: 0
                        val newMessages = source.fetchMessages(channelId, since)
                        
                        newMessages.forEach { msg ->
                            _messages.emit(msg)
                            lastTimestamps[channelId] = maxOf(
                                lastTimestamps[channelId] ?: 0,
                                msg.timestamp
                            )
                        }
                    } catch (e: Exception) {
                        // Log error, continue polling
                    }
                    delay(pollIntervalMs)
                }
            }
            subscriptions[channelId] = job
        }
        
        return messages.filter { it.channelId == channelId }
    }

    fun unsubscribe(channelId: String) {
        subscriptions.remove(channelId)?.cancel()
        lastTimestamps.remove(channelId)
    }

    fun shutdown() {
        subscriptions.values.forEach { it.cancel() }
        subscriptions.clear()
        scope.cancel()
    }
}
