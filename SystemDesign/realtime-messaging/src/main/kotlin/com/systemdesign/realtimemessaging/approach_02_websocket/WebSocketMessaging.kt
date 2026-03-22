/**
 * # Approach 02: WebSocket-Based Messaging
 *
 * ## Pattern Used
 * Persistent WebSocket connection for real-time updates.
 *
 * ## Trade-offs
 * - **Pros:** Real-time, low latency, efficient
 * - **Cons:** Connection management, reconnection logic needed
 */
package com.systemdesign.realtimemessaging.approach_02_websocket

import com.systemdesign.realtimemessaging.approach_01_polling.Message
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING
}

interface WebSocketConnection {
    suspend fun connect(url: String)
    suspend fun send(message: String)
    suspend fun disconnect()
    fun onMessage(handler: (String) -> Unit)
    fun onDisconnect(handler: () -> Unit)
}

class WebSocketMessagingClient(
    private val connection: WebSocketConnection,
    private val url: String,
    private val reconnectDelayMs: Long = 1000,
    private val maxReconnectAttempts: Int = 5,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val subscribedChannels = ConcurrentHashMap.newKeySet<String>()
    private val reconnectAttempts = AtomicInteger(0)
    private val isConnecting = AtomicBoolean(false)
    
    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state
    
    private val _messages = MutableSharedFlow<Message>(extraBufferCapacity = 64)
    val messages: SharedFlow<Message> = _messages

    init {
        connection.onMessage { raw ->
            parseMessage(raw)?.let { _messages.tryEmit(it) }
        }
        
        connection.onDisconnect {
            if (_state.value == ConnectionState.CONNECTED) {
                scope.launch { reconnect() }
            }
        }
    }

    suspend fun connect() {
        if (isConnecting.getAndSet(true)) return
        
        try {
            _state.value = ConnectionState.CONNECTING
            connection.connect(url)
            _state.value = ConnectionState.CONNECTED
            reconnectAttempts.set(0)
            
            subscribedChannels.forEach { channel ->
                connection.send("""{"action":"subscribe","channel":"$channel"}""")
            }
        } catch (e: Exception) {
            _state.value = ConnectionState.DISCONNECTED
            reconnect()
        } finally {
            isConnecting.set(false)
        }
    }

    private suspend fun reconnect() {
        if (reconnectAttempts.incrementAndGet() > maxReconnectAttempts) {
            _state.value = ConnectionState.DISCONNECTED
            return
        }
        
        _state.value = ConnectionState.RECONNECTING
        delay(reconnectDelayMs * reconnectAttempts.get())
        connect()
    }

    suspend fun subscribe(channelId: String): Flow<Message> {
        subscribedChannels.add(channelId)
        
        if (_state.value == ConnectionState.CONNECTED) {
            connection.send("""{"action":"subscribe","channel":"$channelId"}""")
        }
        
        return messages.filter { it.channelId == channelId }
    }

    suspend fun unsubscribe(channelId: String) {
        subscribedChannels.remove(channelId)
        
        if (_state.value == ConnectionState.CONNECTED) {
            connection.send("""{"action":"unsubscribe","channel":"$channelId"}""")
        }
    }

    suspend fun send(channelId: String, content: String) {
        if (_state.value != ConnectionState.CONNECTED) {
            throw IllegalStateException("Not connected")
        }
        connection.send("""{"action":"send","channel":"$channelId","content":"$content"}""")
    }

    suspend fun disconnect() {
        _state.value = ConnectionState.DISCONNECTED
        connection.disconnect()
    }

    private fun parseMessage(raw: String): Message? {
        return try {
            val parts = raw.split("|")
            if (parts.size >= 4) {
                Message(parts[0], parts[1], parts[2], parts[3])
            } else null
        } catch (e: Exception) {
            null
        }
    }

    fun shutdown() {
        scope.launch { disconnect() }
        scope.cancel()
    }
}
