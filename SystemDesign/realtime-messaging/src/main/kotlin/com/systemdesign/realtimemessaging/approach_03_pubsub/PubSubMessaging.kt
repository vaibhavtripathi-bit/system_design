/**
 * # Approach 03: Pub/Sub Messaging
 *
 * ## Pattern Used
 * In-memory pub/sub with topic-based routing.
 *
 * ## Trade-offs
 * - **Pros:** Decoupled, scalable pattern, flexible topics
 * - **Cons:** In-memory only (for this demo), needs external broker for production
 */
package com.systemdesign.realtimemessaging.approach_03_pubsub

import com.systemdesign.realtimemessaging.approach_01_polling.Message
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap

data class Subscription(
    val id: String,
    val topic: String,
    val filter: (Message) -> Boolean = { true }
)

class PubSubMessagingBroker {
    private val topics = ConcurrentHashMap<String, MutableSharedFlow<Message>>()
    private val subscriptions = ConcurrentHashMap<String, MutableList<Subscription>>()

    fun createTopic(topicId: String) {
        topics.putIfAbsent(topicId, MutableSharedFlow(extraBufferCapacity = 64))
    }

    fun deleteTopic(topicId: String) {
        topics.remove(topicId)
        subscriptions.remove(topicId)
    }

    fun publish(topicId: String, message: Message) {
        topics[topicId]?.tryEmit(message)
    }

    fun subscribe(topicId: String, filter: (Message) -> Boolean = { true }): Flow<Message> {
        createTopic(topicId)
        return topics[topicId]!!.filter(filter)
    }

    fun getTopics(): Set<String> = topics.keys.toSet()

    fun clear() {
        topics.clear()
        subscriptions.clear()
    }
}

class PubSubMessagingClient(
    private val broker: PubSubMessagingBroker,
    private val clientId: String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val activeSubscriptions = ConcurrentHashMap<String, Job>()
    
    private val _messages = MutableSharedFlow<Message>(extraBufferCapacity = 64)
    val messages: SharedFlow<Message> = _messages

    fun subscribe(topicId: String): Flow<Message> {
        if (!activeSubscriptions.containsKey(topicId)) {
            val job = scope.launch {
                broker.subscribe(topicId).collect { message ->
                    _messages.emit(message)
                }
            }
            activeSubscriptions[topicId] = job
        }
        return messages.filter { it.channelId == topicId }
    }

    fun unsubscribe(topicId: String) {
        activeSubscriptions.remove(topicId)?.cancel()
    }

    fun publish(topicId: String, content: String) {
        val message = Message(
            id = "${System.currentTimeMillis()}-$clientId",
            channelId = topicId,
            senderId = clientId,
            content = content
        )
        broker.publish(topicId, message)
    }

    fun shutdown() {
        activeSubscriptions.values.forEach { it.cancel() }
        activeSubscriptions.clear()
        scope.cancel()
    }
}
