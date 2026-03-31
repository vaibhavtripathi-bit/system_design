package com.systemdesign.chatsystem.approach_02_mediator_rooms

import com.systemdesign.chatsystem.common.*
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Approach 2: Mediator Pattern for Chat Room Management
 *
 * Each ChatRoom acts as a mediator between its participants. Participants never
 * communicate directly—they send messages through the room, which handles routing,
 * access control, group management, and history. This decouples participants from
 * each other and centralizes room-level policy.
 *
 * Pattern: Mediator
 *
 * Trade-offs:
 * + Participants are fully decoupled; adding/removing is trivial
 * + Room encapsulates all routing, admin, and history logic
 * + Easy to enforce per-room policies (mute, admin-only, etc.)
 * + Group operations (kick, promote) are centralized
 * - Mediator can become a god object if not decomposed
 * - All traffic funnels through the mediator (potential bottleneck)
 * - Harder to implement cross-room features (global search, mentions)
 *
 * When to use:
 * - Group chat with admin controls and moderation
 * - When room-level policies differ (e.g., read-only announcements vs. open chat)
 * - When participant lifecycle (join/leave/kick) is a first-class concern
 *
 * Extensibility:
 * - New room policy: Implement RoomPolicy and attach to MediatedChatRoom
 * - New participant role: Extend ParticipantRole enum
 * - Cross-room features: Use ChatRoomRegistry as a higher-level coordinator
 */

enum class ParticipantRole {
    MEMBER,
    ADMIN,
    OWNER
}

data class Participant(
    val userId: String,
    val displayName: String,
    val role: ParticipantRole = ParticipantRole.MEMBER,
    val joinedAt: LocalDateTime = LocalDateTime.now(),
    val muted: Boolean = false,
    val mutedUntil: LocalDateTime? = null
) {
    fun isMuted(): Boolean {
        if (!muted) return false
        return mutedUntil == null || mutedUntil.isAfter(LocalDateTime.now())
    }

    fun canSendMessages(): Boolean = !isMuted()

    fun isAdmin(): Boolean = role in setOf(ParticipantRole.ADMIN, ParticipantRole.OWNER)

    fun isOwner(): Boolean = role == ParticipantRole.OWNER
}

interface RoomPolicy {
    fun canJoin(participant: Participant, room: MediatedChatRoom): Boolean
    fun canSend(participant: Participant, room: MediatedChatRoom): Boolean
    fun canInvite(participant: Participant, room: MediatedChatRoom): Boolean
}

class DefaultRoomPolicy : RoomPolicy {
    override fun canJoin(participant: Participant, room: MediatedChatRoom): Boolean = true

    override fun canSend(participant: Participant, room: MediatedChatRoom): Boolean =
        participant.canSendMessages()

    override fun canInvite(participant: Participant, room: MediatedChatRoom): Boolean =
        participant.isAdmin()
}

class OpenRoomPolicy : RoomPolicy {
    override fun canJoin(participant: Participant, room: MediatedChatRoom): Boolean = true
    override fun canSend(participant: Participant, room: MediatedChatRoom): Boolean = true
    override fun canInvite(participant: Participant, room: MediatedChatRoom): Boolean = true
}

class AdminOnlyPostPolicy : RoomPolicy {
    override fun canJoin(participant: Participant, room: MediatedChatRoom): Boolean = true

    override fun canSend(participant: Participant, room: MediatedChatRoom): Boolean =
        participant.isAdmin()

    override fun canInvite(participant: Participant, room: MediatedChatRoom): Boolean =
        participant.isAdmin()
}

sealed class RoomEvent {
    abstract val roomId: String
    abstract val timestamp: LocalDateTime

    data class MessageSent(
        override val roomId: String, val message: Message,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : RoomEvent()

    data class ParticipantJoined(
        override val roomId: String, val participant: Participant,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : RoomEvent()

    data class ParticipantLeft(
        override val roomId: String, val userId: String,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : RoomEvent()

    data class ParticipantKicked(
        override val roomId: String, val userId: String, val kickedBy: String,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : RoomEvent()

    data class RoleChanged(
        override val roomId: String, val userId: String,
        val newRole: ParticipantRole, val changedBy: String,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : RoomEvent()

    data class ParticipantMuted(
        override val roomId: String, val userId: String,
        val mutedBy: String, val until: LocalDateTime?,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : RoomEvent()
}

interface RoomEventListener {
    fun onEvent(event: RoomEvent)
}

class MediatedChatRoom(
    val id: String,
    val name: String,
    val type: ChatRoomType,
    private var policy: RoomPolicy = DefaultRoomPolicy()
) {
    private val participants = ConcurrentHashMap<String, Participant>()
    private val messageHistory = mutableListOf<Message>()
    private val eventListeners = mutableListOf<RoomEventListener>()
    private val pinnedMessageIds = mutableSetOf<String>()

    fun addEventListener(listener: RoomEventListener) { eventListeners.add(listener) }
    fun setPolicy(newPolicy: RoomPolicy) { policy = newPolicy }

    fun addParticipant(participant: Participant): Boolean {
        if (!policy.canJoin(participant, this)) return false
        if (type == ChatRoomType.DIRECT && participants.size >= 2) return false
        participants[participant.userId] = participant
        messageHistory.add(Message(
            roomId = id, senderId = "system",
            content = "${participant.displayName} joined the room", type = MessageType.SYSTEM
        ))
        emitEvent(RoomEvent.ParticipantJoined(id, participant))
        return true
    }

    fun removeParticipant(userId: String): Boolean {
        val removed = participants.remove(userId) ?: return false
        messageHistory.add(Message(
            roomId = id, senderId = "system",
            content = "${removed.displayName} left the room", type = MessageType.SYSTEM
        ))
        emitEvent(RoomEvent.ParticipantLeft(id, userId))
        return true
    }

    fun kickParticipant(targetUserId: String, adminUserId: String): Boolean {
        val admin = participants[adminUserId] ?: return false
        if (!admin.isAdmin()) return false
        val target = participants[targetUserId] ?: return false
        if (target.isOwner()) return false

        participants.remove(targetUserId)
        messageHistory.add(Message(
            roomId = id, senderId = "system",
            content = "${target.displayName} was removed by ${admin.displayName}",
            type = MessageType.SYSTEM
        ))
        emitEvent(RoomEvent.ParticipantKicked(id, targetUserId, adminUserId))
        return true
    }

    fun sendMessage(senderId: String, content: String, type: MessageType = MessageType.TEXT): Message? {
        val participant = participants[senderId] ?: return null
        if (!policy.canSend(participant, this)) return null
        val message = Message(roomId = id, senderId = senderId, content = content, type = type)
        messageHistory.add(message)
        emitEvent(RoomEvent.MessageSent(id, message))
        return message
    }

    fun changeRole(targetUserId: String, newRole: ParticipantRole, changedBy: String): Boolean {
        val changer = participants[changedBy] ?: return false
        if (!changer.isOwner() && newRole == ParticipantRole.OWNER) return false
        if (!changer.isAdmin()) return false
        val target = participants[targetUserId] ?: return false
        if (target.isOwner() && !changer.isOwner()) return false
        participants[targetUserId] = target.copy(role = newRole)
        emitEvent(RoomEvent.RoleChanged(id, targetUserId, newRole, changedBy))
        return true
    }

    fun muteParticipant(targetUserId: String, mutedBy: String, until: LocalDateTime? = null): Boolean {
        val admin = participants[mutedBy] ?: return false
        if (!admin.isAdmin()) return false
        val target = participants[targetUserId] ?: return false
        if (target.isAdmin()) return false
        participants[targetUserId] = target.copy(muted = true, mutedUntil = until)
        emitEvent(RoomEvent.ParticipantMuted(id, targetUserId, mutedBy, until))
        return true
    }

    fun unmuteParticipant(targetUserId: String, unmutedBy: String): Boolean {
        val admin = participants[unmutedBy] ?: return false
        if (!admin.isAdmin()) return false
        val target = participants[targetUserId] ?: return false
        participants[targetUserId] = target.copy(muted = false, mutedUntil = null)
        return true
    }

    fun pinMessage(messageId: String, userId: String): Boolean {
        val participant = participants[userId] ?: return false
        if (!participant.isAdmin()) return false
        if (messageHistory.none { it.id == messageId }) return false
        return pinnedMessageIds.add(messageId)
    }

    fun unpinMessage(messageId: String, userId: String): Boolean {
        val participant = participants[userId] ?: return false
        if (!participant.isAdmin()) return false
        return pinnedMessageIds.remove(messageId)
    }

    fun getPinnedMessages(): List<Message> = messageHistory.filter { it.id in pinnedMessageIds }
    fun getParticipant(userId: String): Participant? = participants[userId]
    fun getParticipants(): List<Participant> = participants.values.toList()
    fun getParticipantCount(): Int = participants.size
    fun getMessages(limit: Int = 50): List<Message> = messageHistory.takeLast(limit)
    fun searchMessages(query: String): List<Message> = messageHistory.filter { it.content.contains(query, ignoreCase = true) && !it.deleted }
    fun getMessageById(messageId: String): Message? = messageHistory.find { it.id == messageId }
    private fun emitEvent(event: RoomEvent) { eventListeners.forEach { it.onEvent(event) } }
}

class ChatRoomRegistry {
    private val rooms = ConcurrentHashMap<String, MediatedChatRoom>()
    private val userRoomIndex = ConcurrentHashMap<String, MutableSet<String>>()

    fun createRoom(
        name: String,
        type: ChatRoomType,
        creator: Participant,
        policy: RoomPolicy = DefaultRoomPolicy()
    ): MediatedChatRoom {
        val ownerParticipant = creator.copy(role = ParticipantRole.OWNER)
        val room = MediatedChatRoom(
            id = java.util.UUID.randomUUID().toString(),
            name = name,
            type = type,
            policy = policy
        )
        room.addParticipant(ownerParticipant)
        rooms[room.id] = room
        userRoomIndex.getOrPut(creator.userId) { mutableSetOf() }.add(room.id)
        return room
    }

    fun createDirectRoom(user1: Participant, user2: Participant): MediatedChatRoom {
        val existing = findDirectRoom(user1.userId, user2.userId)
        if (existing != null) return existing

        val room = MediatedChatRoom(
            id = java.util.UUID.randomUUID().toString(),
            name = "${user1.displayName} & ${user2.displayName}",
            type = ChatRoomType.DIRECT,
            policy = OpenRoomPolicy()
        )
        room.addParticipant(user1)
        room.addParticipant(user2)
        rooms[room.id] = room
        userRoomIndex.getOrPut(user1.userId) { mutableSetOf() }.add(room.id)
        userRoomIndex.getOrPut(user2.userId) { mutableSetOf() }.add(room.id)
        return room
    }

    private fun findDirectRoom(userId1: String, userId2: String): MediatedChatRoom? {
        val user1Rooms = userRoomIndex[userId1] ?: return null
        return user1Rooms
            .mapNotNull { rooms[it] }
            .find { room ->
                room.type == ChatRoomType.DIRECT &&
                    room.getParticipants().map { it.userId }.toSet() == setOf(userId1, userId2)
            }
    }

    fun getRoom(roomId: String): MediatedChatRoom? = rooms[roomId]

    fun getUserRooms(userId: String): List<MediatedChatRoom> =
        userRoomIndex[userId]?.mapNotNull { rooms[it] } ?: emptyList()

    fun joinRoom(roomId: String, participant: Participant): Boolean {
        val room = rooms[roomId] ?: return false
        val joined = room.addParticipant(participant)
        if (joined) {
            userRoomIndex.getOrPut(participant.userId) { mutableSetOf() }.add(roomId)
        }
        return joined
    }

    fun leaveRoom(roomId: String, userId: String): Boolean {
        val room = rooms[roomId] ?: return false
        val left = room.removeParticipant(userId)
        if (left) {
            userRoomIndex[userId]?.remove(roomId)
        }
        return left
    }

    fun deleteRoom(roomId: String): Boolean {
        val room = rooms.remove(roomId) ?: return false
        room.getParticipants().forEach { participant ->
            userRoomIndex[participant.userId]?.remove(roomId)
        }
        return true
    }

    fun getRoomCount(): Int = rooms.size
}

class EventLog : RoomEventListener {
    private val events = mutableListOf<RoomEvent>()
    override fun onEvent(event: RoomEvent) { events.add(event) }
    fun getEvents(): List<RoomEvent> = events.toList()
    fun getEventsForRoom(roomId: String): List<RoomEvent> = events.filter { it.roomId == roomId }
    fun clear() = events.clear()
}
