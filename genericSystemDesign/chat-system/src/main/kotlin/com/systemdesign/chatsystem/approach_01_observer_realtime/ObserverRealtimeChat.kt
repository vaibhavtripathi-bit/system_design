package com.systemdesign.chatsystem.approach_01_observer_realtime

import com.systemdesign.chatsystem.common.*
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Approach 1: Observer Pattern for Real-Time Messaging
 *
 * ChatServer acts as the subject, maintaining a registry of connected clients
 * (observers). When events occur—new messages, read receipts, typing indicators,
 * presence changes—the server notifies all relevant observers in real time.
 *
 * Pattern: Observer
 *
 * Trade-offs:
 * + Natural fit for real-time push: server broadcasts, clients react
 * + Decouples event producers from consumers
 * + Easy to add new event types without changing existing observers
 * + Supports selective notification (per-room, per-user)
 * - All observers notified even if only some care (mitigated by room-scoped dispatch)
 * - Observer list management adds memory overhead per connection
 * - No built-in ordering guarantee across observers
 *
 * When to use:
 * - Real-time messaging where clients need instant updates
 * - Systems with many concurrent connections (WebSocket-style)
 * - When presence and typing indicators are first-class features
 *
 * Extensibility:
 * - New event type: Add method to ChatObserver, implement in server dispatch
 * - Filtered subscriptions: Use RoomScopedObserver wrapper
 * - Event replay: Combine with message store for catch-up on reconnect
 */

class ChatClient(
    val user: User,
    private val observer: ChatObserver
) : ChatObserver by observer {

    private var connected = false

    fun isConnected(): Boolean = connected

    fun connect() {
        connected = true
    }

    fun disconnect() {
        connected = false
    }
}

class ChatServer {

    private val clients = ConcurrentHashMap<String, ChatClient>()
    private val rooms = ConcurrentHashMap<String, ChatRoom>()
    private val messages = ConcurrentHashMap<String, MutableList<Message>>()
    private val presenceMap = ConcurrentHashMap<String, PresenceStatus>()
    private val readReceipts = ConcurrentHashMap<String, MutableMap<String, ReadReceipt>>()
    private val typingStates = ConcurrentHashMap<String, MutableSet<String>>()

    fun registerClient(client: ChatClient) {
        clients[client.user.id] = client
        client.connect()
        updatePresence(client.user.id, PresenceStatus.ONLINE)
    }

    fun unregisterClient(userId: String) {
        clients[userId]?.disconnect()
        clients.remove(userId)
        updatePresence(userId, PresenceStatus.OFFLINE)
    }

    fun getClient(userId: String): ChatClient? = clients[userId]

    fun createRoom(room: ChatRoom): ChatRoom {
        rooms[room.id] = room
        messages[room.id] = mutableListOf()
        return room
    }

    fun getRoom(roomId: String): ChatRoom? = rooms[roomId]

    fun sendMessage(senderId: String, roomId: String, content: String, type: MessageType = MessageType.TEXT): Message {
        val room = rooms[roomId]
            ?: throw IllegalArgumentException("Room $roomId does not exist")
        require(room.participantIds.contains(senderId)) { "User $senderId is not in room $roomId" }

        val message = Message(
            roomId = roomId,
            senderId = senderId,
            content = content,
            type = type
        )

        messages.getOrPut(roomId) { mutableListOf() }.add(message)

        clearTyping(senderId, roomId)

        notifyRoom(roomId, excludeUserId = senderId) { observer ->
            observer.onMessageReceived(message)
        }

        val deliveredMessage = markDelivered(message, room)
        return deliveredMessage
    }

    private fun markDelivered(message: Message, room: ChatRoom): Message {
        val onlineRecipients = room.participantIds
            .filter { it != message.senderId }
            .filter { clients[it]?.isConnected() == true }

        if (onlineRecipients.isEmpty()) return message

        val delivered = message.copy(status = MessageStatus.DELIVERED)
        updateStoredMessage(delivered)
        return delivered
    }

    fun markAsRead(userId: String, roomId: String, messageId: String) {
        val room = rooms[roomId] ?: return
        require(room.participantIds.contains(userId)) { "User $userId is not in room $roomId" }

        val receipt = ReadReceipt(
            userId = userId,
            roomId = roomId,
            lastReadMessageId = messageId
        )

        readReceipts.getOrPut(roomId) { mutableMapOf() }[userId] = receipt

        updateMessagesAsRead(userId, roomId, messageId)

        notifyRoom(roomId) { observer ->
            observer.onReadReceiptUpdated(receipt)
        }
    }

    private fun updateMessagesAsRead(userId: String, roomId: String, upToMessageId: String) {
        val roomMessages = messages[roomId] ?: return
        var found = false

        for (i in roomMessages.indices.reversed()) {
            val msg = roomMessages[i]
            if (msg.id == upToMessageId) found = true
            if (found && msg.senderId != userId && msg.status != MessageStatus.READ) {
                roomMessages[i] = msg.copy(status = MessageStatus.READ)
            }
        }
    }

    fun setTyping(userId: String, roomId: String, isTyping: Boolean) {
        val room = rooms[roomId] ?: return
        require(room.participantIds.contains(userId))

        val roomTyping = typingStates.getOrPut(roomId) { mutableSetOf() }
        if (isTyping) roomTyping.add(userId) else roomTyping.remove(userId)

        val indicator = TypingIndicator(
            userId = userId,
            roomId = roomId,
            isTyping = isTyping
        )

        notifyRoom(roomId, excludeUserId = userId) { observer ->
            observer.onTypingStatusChanged(indicator)
        }
    }

    private fun clearTyping(userId: String, roomId: String) {
        val roomTyping = typingStates[roomId] ?: return
        if (roomTyping.remove(userId)) {
            val indicator = TypingIndicator(userId = userId, roomId = roomId, isTyping = false)
            notifyRoom(roomId, excludeUserId = userId) { observer ->
                observer.onTypingStatusChanged(indicator)
            }
        }
    }

    fun updatePresence(userId: String, status: PresenceStatus) {
        val previous = presenceMap.put(userId, status)
        if (previous == status) return

        val userRooms = rooms.values.filter { it.participantIds.contains(userId) }
        val notifiedUsers = userRooms.flatMap { it.participantIds }.toSet() - userId

        notifiedUsers.forEach { peerId ->
            val client = clients[peerId]
            if (client != null && client.isConnected()) {
                client.onPresenceChanged(userId, status)
            }
        }
    }

    fun getPresence(userId: String): PresenceStatus =
        presenceMap[userId] ?: PresenceStatus.OFFLINE

    fun getTypingUsers(roomId: String): Set<String> =
        typingStates[roomId]?.toSet() ?: emptySet()

    fun getReadReceipt(roomId: String, userId: String): ReadReceipt? =
        readReceipts[roomId]?.get(userId)

    fun getMessages(roomId: String, limit: Int = 50): List<Message> =
        messages[roomId]?.takeLast(limit) ?: emptyList()

    fun getMessageById(roomId: String, messageId: String): Message? =
        messages[roomId]?.find { it.id == messageId }

    private fun updateStoredMessage(message: Message) {
        val roomMessages = messages[message.roomId] ?: return
        val index = roomMessages.indexOfFirst { it.id == message.id }
        if (index >= 0) roomMessages[index] = message
    }

    private fun notifyRoom(
        roomId: String,
        excludeUserId: String? = null,
        action: (ChatObserver) -> Unit
    ) {
        val room = rooms[roomId] ?: return
        room.participantIds
            .filter { it != excludeUserId }
            .mapNotNull { clients[it] }
            .filter { it.isConnected() }
            .forEach { action(it) }
    }
}

class RecordingObserver : ChatObserver {
    val receivedMessages = mutableListOf<Message>()
    val updatedMessages = mutableListOf<Message>()
    val deletedMessageIds = mutableListOf<Pair<String, String>>()
    val typingIndicators = mutableListOf<TypingIndicator>()
    val presenceChanges = mutableListOf<Pair<String, PresenceStatus>>()
    val readReceipts = mutableListOf<ReadReceipt>()

    override fun onMessageReceived(message: Message) {
        receivedMessages.add(message)
    }

    override fun onMessageUpdated(message: Message) {
        updatedMessages.add(message)
    }

    override fun onMessageDeleted(messageId: String, roomId: String) {
        deletedMessageIds.add(messageId to roomId)
    }

    override fun onTypingStatusChanged(indicator: TypingIndicator) {
        typingIndicators.add(indicator)
    }

    override fun onPresenceChanged(userId: String, status: PresenceStatus) {
        presenceChanges.add(userId to status)
    }

    override fun onReadReceiptUpdated(receipt: ReadReceipt) {
        readReceipts.add(receipt)
    }

    fun clear() {
        receivedMessages.clear()
        updatedMessages.clear()
        deletedMessageIds.clear()
        typingIndicators.clear()
        presenceChanges.clear()
        readReceipts.clear()
    }
}

class RoomScopedObserver(
    private val targetRoomId: String,
    private val delegate: ChatObserver
) : ChatObserver {

    override fun onMessageReceived(message: Message) {
        if (message.roomId == targetRoomId) delegate.onMessageReceived(message)
    }

    override fun onMessageUpdated(message: Message) {
        if (message.roomId == targetRoomId) delegate.onMessageUpdated(message)
    }

    override fun onMessageDeleted(messageId: String, roomId: String) {
        if (roomId == targetRoomId) delegate.onMessageDeleted(messageId, roomId)
    }

    override fun onTypingStatusChanged(indicator: TypingIndicator) {
        if (indicator.roomId == targetRoomId) delegate.onTypingStatusChanged(indicator)
    }

    override fun onPresenceChanged(userId: String, status: PresenceStatus) {
        delegate.onPresenceChanged(userId, status)
    }

    override fun onReadReceiptUpdated(receipt: ReadReceipt) {
        if (receipt.roomId == targetRoomId) delegate.onReadReceiptUpdated(receipt)
    }
}

class PresenceTracker(private val server: ChatServer) {
    fun setAway(userId: String) { server.updatePresence(userId, PresenceStatus.AWAY) }
    fun setOnline(userId: String) { server.updatePresence(userId, PresenceStatus.ONLINE) }
    fun setOffline(userId: String) { server.updatePresence(userId, PresenceStatus.OFFLINE) }

    fun getOnlineUsers(roomId: String): Set<String> {
        val room = server.getRoom(roomId) ?: return emptySet()
        return room.participantIds.filter { server.getPresence(it) == PresenceStatus.ONLINE }.toSet()
    }

    fun getPresenceMap(roomId: String): Map<String, PresenceStatus> {
        val room = server.getRoom(roomId) ?: return emptyMap()
        return room.participantIds.associateWith { server.getPresence(it) }
    }
}

class MessageHistory(private val server: ChatServer) {

    fun getConversation(roomId: String, limit: Int = 50, beforeMessageId: String? = null): List<Message> {
        val all = server.getMessages(roomId, Int.MAX_VALUE)
        val filtered = if (beforeMessageId != null) {
            val idx = all.indexOfFirst { it.id == beforeMessageId }
            if (idx > 0) all.subList(0, idx) else all
        } else {
            all
        }
        return filtered.takeLast(limit)
    }

    fun searchMessages(roomId: String, query: String): List<Message> {
        return server.getMessages(roomId, Int.MAX_VALUE)
            .filter { !it.deleted && it.content.contains(query, ignoreCase = true) }
    }

    fun getUnreadCount(roomId: String, userId: String): Int {
        val receipt = server.getReadReceipt(roomId, userId)
        val allMessages = server.getMessages(roomId, Int.MAX_VALUE)
            .filter { it.senderId != userId && !it.deleted }

        if (receipt == null) return allMessages.size

        val lastReadIdx = allMessages.indexOfFirst { it.id == receipt.lastReadMessageId }
        return if (lastReadIdx >= 0) allMessages.size - lastReadIdx - 1 else allMessages.size
    }
}
