package com.systemdesign.chatsystem.common

import java.time.LocalDateTime
import java.util.UUID

/**
 * Core domain models for Chat System.
 *
 * Extensibility Points:
 * - New message types: Add to MessageType enum
 * - New presence states: Add to PresenceStatus enum
 * - New chat features: Extend ChatObserver interface
 * - New room types: Add to ChatRoomType enum
 *
 * Breaking Changes Required For:
 * - Changing message ID structure
 * - Modifying ChatRoom participant model
 */

enum class MessageType {
    TEXT,
    IMAGE,
    FILE,
    SYSTEM
}

enum class MessageStatus {
    SENT,
    DELIVERED,
    READ
}

enum class PresenceStatus {
    ONLINE,
    OFFLINE,
    AWAY
}

enum class ChatRoomType {
    DIRECT,
    GROUP
}

data class User(
    val id: String = UUID.randomUUID().toString(),
    val username: String,
    val displayName: String = username,
    val presence: PresenceStatus = PresenceStatus.OFFLINE,
    val lastSeenAt: LocalDateTime = LocalDateTime.now()
)

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val roomId: String,
    val senderId: String,
    val content: String,
    val type: MessageType = MessageType.TEXT,
    val status: MessageStatus = MessageStatus.SENT,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val editedAt: LocalDateTime? = null,
    val deleted: Boolean = false,
    val reactions: Map<String, Set<String>> = emptyMap(),
    val pinned: Boolean = false
)

data class ChatRoom(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: ChatRoomType,
    val participantIds: Set<String>,
    val adminIds: Set<String> = emptySet(),
    val createdAt: LocalDateTime = LocalDateTime.now()
)

data class ReadReceipt(
    val userId: String,
    val roomId: String,
    val lastReadMessageId: String,
    val timestamp: LocalDateTime = LocalDateTime.now()
)

data class TypingIndicator(
    val userId: String,
    val roomId: String,
    val isTyping: Boolean,
    val timestamp: LocalDateTime = LocalDateTime.now()
)

interface ChatObserver {
    fun onMessageReceived(message: Message)
    fun onMessageUpdated(message: Message)
    fun onMessageDeleted(messageId: String, roomId: String)
    fun onTypingStatusChanged(indicator: TypingIndicator)
    fun onPresenceChanged(userId: String, status: PresenceStatus)
    fun onReadReceiptUpdated(receipt: ReadReceipt)
}
