package com.systemdesign.chatsystem

import com.systemdesign.chatsystem.common.*
import com.systemdesign.chatsystem.approach_01_observer_realtime.*
import com.systemdesign.chatsystem.approach_02_mediator_rooms.*
import com.systemdesign.chatsystem.approach_03_command_messaging.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import java.time.LocalDateTime

class ChatSystemTest {

    private fun createUser(id: String, name: String) = User(
        id = id,
        username = name,
        displayName = name
    )

    @Nested
    inner class ObserverRealtimeChatTest {

        private lateinit var server: ChatServer
        private lateinit var aliceObserver: RecordingObserver
        private lateinit var bobObserver: RecordingObserver
        private lateinit var alice: ChatClient
        private lateinit var bob: ChatClient
        private lateinit var room: ChatRoom

        @BeforeEach
        fun setup() {
            server = ChatServer()
            aliceObserver = RecordingObserver()
            bobObserver = RecordingObserver()
            alice = ChatClient(createUser("alice", "Alice"), aliceObserver)
            bob = ChatClient(createUser("bob", "Bob"), bobObserver)

            server.registerClient(alice)
            server.registerClient(bob)

            room = server.createRoom(
                ChatRoom(
                    id = "room-1",
                    name = "General",
                    type = ChatRoomType.GROUP,
                    participantIds = setOf("alice", "bob")
                )
            )
        }

        @Test
        fun `sending a message notifies other participants`() {
            server.sendMessage("alice", room.id, "Hello Bob!")

            assertEquals(1, bobObserver.receivedMessages.size)
            assertEquals("Hello Bob!", bobObserver.receivedMessages[0].content)
            assertEquals("alice", bobObserver.receivedMessages[0].senderId)
        }

        @Test
        fun `sender does not receive own message notification`() {
            server.sendMessage("alice", room.id, "Hello!")

            assertEquals(0, aliceObserver.receivedMessages.size)
            assertEquals(1, bobObserver.receivedMessages.size)
        }

        @Test
        fun `messages stored in history with correct order`() {
            server.sendMessage("alice", room.id, "First")
            server.sendMessage("bob", room.id, "Second")
            val messages = server.getMessages(room.id)
            assertEquals(2, messages.size)
            assertEquals("First", messages[0].content)
        }

        @Test
        fun `message delivered to online recipients gets DELIVERED status`() {
            assertEquals(MessageStatus.DELIVERED, server.sendMessage("alice", room.id, "Hello!").status)
        }

        @Test
        fun `disconnected client does not receive notifications`() {
            server.unregisterClient("bob")
            server.sendMessage("alice", room.id, "Are you there?")
            assertEquals(0, bobObserver.receivedMessages.size)
        }

        @Test
        fun `typing indicator notifies others but not the typer`() {
            server.setTyping("alice", room.id, true)
            assertEquals(1, bobObserver.typingIndicators.size)
            assertTrue(bobObserver.typingIndicators[0].isTyping)
            assertEquals(0, aliceObserver.typingIndicators.size)
        }

        @Test
        fun `sending a message clears typing indicator`() {
            server.setTyping("alice", room.id, true)
            bobObserver.clear()
            server.sendMessage("alice", room.id, "Done typing")
            assertNotNull(bobObserver.typingIndicators.find { !it.isTyping })
        }

        @Test
        fun `typing users tracked per room`() {
            server.setTyping("alice", room.id, true)
            assertEquals(setOf("alice"), server.getTypingUsers(room.id))
            server.setTyping("alice", room.id, false)
            assertEquals(emptySet<String>(), server.getTypingUsers(room.id))
        }

        @Test
        fun `read receipt notifies sender and updates status`() {
            val msg = server.sendMessage("alice", room.id, "Read this")
            server.markAsRead("bob", room.id, msg.id)
            assertEquals(1, aliceObserver.readReceipts.size)
            assertEquals("bob", aliceObserver.readReceipts[0].userId)
            assertEquals(MessageStatus.READ, server.getMessageById(room.id, msg.id)?.status)
        }

        @Test
        fun `presence change notifies peers and tracks correctly`() {
            assertEquals(PresenceStatus.ONLINE, server.getPresence("alice"))
            server.updatePresence("alice", PresenceStatus.AWAY)
            assertNotNull(bobObserver.presenceChanges.find { it.first == "alice" && it.second == PresenceStatus.AWAY })
            server.unregisterClient("alice")
            assertEquals(PresenceStatus.OFFLINE, server.getPresence("alice"))
        }

        @Test
        fun `group chat with three participants`() {
            val charlieObserver = RecordingObserver()
            val charlie = ChatClient(createUser("charlie", "Charlie"), charlieObserver)
            server.registerClient(charlie)
            val groupRoom = server.createRoom(ChatRoom(
                id = "group-1", name = "Team", type = ChatRoomType.GROUP,
                participantIds = setOf("alice", "bob", "charlie")
            ))
            server.sendMessage("alice", groupRoom.id, "Hello team!")
            assertEquals(1, bobObserver.receivedMessages.size)
            assertEquals(1, charlieObserver.receivedMessages.size)
            assertEquals(0, aliceObserver.receivedMessages.size)
        }

        @Test
        fun `non-participant and non-existent room throw`() {
            assertThrows(IllegalArgumentException::class.java) { server.sendMessage("alice", "no-such-room", "Hello") }
            val outsider = ChatClient(createUser("outsider", "Outsider"), RecordingObserver())
            server.registerClient(outsider)
            assertThrows(IllegalArgumentException::class.java) { server.sendMessage("outsider", room.id, "Sneaky") }
        }

        @Test
        fun `presence tracker reports online users`() {
            val tracker = PresenceTracker(server)
            assertEquals(setOf("alice", "bob"), tracker.getOnlineUsers(room.id))
            tracker.setAway("alice")
            assertEquals(setOf("bob"), tracker.getOnlineUsers(room.id))
        }

        @Test
        fun `message history search and unread count`() {
            val msg1 = server.sendMessage("alice", room.id, "Hello world")
            server.sendMessage("bob", room.id, "Goodbye world")
            server.sendMessage("alice", room.id, "Hello again")
            val history = MessageHistory(server)
            assertEquals(2, history.searchMessages(room.id, "Hello").size)
            assertEquals(2, history.getUnreadCount(room.id, "bob"))
            server.markAsRead("bob", room.id, msg1.id)
            assertEquals(1, history.getUnreadCount(room.id, "bob"))
        }

        @Test
        fun `room scoped observer filters by room`() {
            val innerObserver = RecordingObserver()
            val scoped = RoomScopedObserver("room-1", innerObserver)
            scoped.onMessageReceived(Message(roomId = "room-1", senderId = "alice", content = "In scope"))
            scoped.onMessageReceived(Message(roomId = "room-2", senderId = "alice", content = "Out of scope"))
            assertEquals(1, innerObserver.receivedMessages.size)
        }
    }

    @Nested
    inner class MediatorRoomsChatTest {

        private lateinit var registry: ChatRoomRegistry
        private lateinit var eventLog: EventLog
        private lateinit var aliceParticipant: Participant
        private lateinit var bobParticipant: Participant

        @BeforeEach
        fun setup() {
            registry = ChatRoomRegistry()
            eventLog = EventLog()
            aliceParticipant = Participant(userId = "alice", displayName = "Alice")
            bobParticipant = Participant(userId = "bob", displayName = "Bob")
        }

        @Test
        fun `create group room, send message, creator is OWNER`() {
            val room = registry.createRoom("General", ChatRoomType.GROUP, aliceParticipant)
            registry.joinRoom(room.id, bobParticipant)
            assertEquals(ParticipantRole.OWNER, room.getParticipant("alice")?.role)
            val msg = room.sendMessage("alice", "Hello group!")
            assertNotNull(msg)
            assertEquals("Hello group!", msg!!.content)
            assertNull(room.sendMessage("outsider", "Sneaky"))
        }

        @Test
        fun `admin kick and role management`() {
            val room = registry.createRoom("General", ChatRoomType.GROUP, aliceParticipant)
            registry.joinRoom(room.id, bobParticipant)
            val charlie = Participant(userId = "charlie", displayName = "Charlie")
            room.addParticipant(charlie)

            assertFalse(room.kickParticipant("charlie", "bob"))
            assertTrue(room.kickParticipant("charlie", "alice"))
            assertFalse(room.kickParticipant("alice", "bob"))
        }

        @Test
        fun `owner can change roles, non-owner cannot promote to OWNER`() {
            val room = registry.createRoom("General", ChatRoomType.GROUP, aliceParticipant)
            registry.joinRoom(room.id, bobParticipant)
            assertTrue(room.changeRole("bob", ParticipantRole.ADMIN, "alice"))
            assertEquals(ParticipantRole.ADMIN, room.getParticipant("bob")?.role)
            val charlie = Participant(userId = "charlie", displayName = "Charlie")
            room.addParticipant(charlie)
            assertFalse(room.changeRole("charlie", ParticipantRole.OWNER, "bob"))
        }

        @Test
        fun `mute and unmute participants`() {
            val room = registry.createRoom("General", ChatRoomType.GROUP, aliceParticipant)
            registry.joinRoom(room.id, bobParticipant)
            room.muteParticipant("bob", "alice")
            assertNull(room.sendMessage("bob", "I'm muted"))
            room.unmuteParticipant("bob", "alice")
            assertNotNull(room.sendMessage("bob", "I'm back!"))
        }

        @Test
        fun `admin cannot mute another admin`() {
            val room = registry.createRoom("General", ChatRoomType.GROUP, aliceParticipant)
            room.addParticipant(bobParticipant.copy(role = ParticipantRole.ADMIN))
            assertFalse(room.muteParticipant("bob", "alice"))
        }

        @Test
        fun `direct room limited to two and reuses existing`() {
            val room1 = registry.createDirectRoom(aliceParticipant, bobParticipant)
            assertFalse(room1.addParticipant(Participant(userId = "charlie", displayName = "Charlie")))
            assertEquals(2, room1.getParticipantCount())
            assertEquals(room1.id, registry.createDirectRoom(aliceParticipant, bobParticipant).id)
        }

        @Test
        fun `admin-only post policy restricts sending`() {
            val room = registry.createRoom("Announcements", ChatRoomType.GROUP, aliceParticipant, policy = AdminOnlyPostPolicy())
            registry.joinRoom(room.id, bobParticipant)
            assertNotNull(room.sendMessage("alice", "Announcement!"))
            assertNull(room.sendMessage("bob", "Reply"))
        }

        @Test
        fun `event log captures room events`() {
            val room = registry.createRoom("General", ChatRoomType.GROUP, aliceParticipant)
            room.addEventListener(eventLog)
            registry.joinRoom(room.id, bobParticipant)
            room.sendMessage("alice", "Hello!")
            assertTrue(eventLog.getEvents().any { it is RoomEvent.ParticipantJoined })
            assertTrue(eventLog.getEvents().any { it is RoomEvent.MessageSent })
        }

        @Test
        fun `pin and unpin messages, non-admin cannot pin`() {
            val room = registry.createRoom("General", ChatRoomType.GROUP, aliceParticipant)
            registry.joinRoom(room.id, bobParticipant)
            val msg = room.sendMessage("alice", "Important!")!!
            assertFalse(room.pinMessage(msg.id, "bob"))
            assertTrue(room.pinMessage(msg.id, "alice"))
            assertEquals(1, room.getPinnedMessages().size)
            assertTrue(room.unpinMessage(msg.id, "alice"))
            assertEquals(0, room.getPinnedMessages().size)
        }

        @Test
        fun `search messages and registry tracking`() {
            val room = registry.createRoom("General", ChatRoomType.GROUP, aliceParticipant)
            registry.joinRoom(room.id, bobParticipant)
            room.sendMessage("alice", "Hello world")
            room.sendMessage("bob", "Goodbye world")
            room.sendMessage("alice", "Hello again")
            assertEquals(2, room.searchMessages("Hello").size)
            registry.createRoom("Room B", ChatRoomType.GROUP, aliceParticipant)
            assertEquals(2, registry.getUserRooms("alice").size)
        }

        @Test
        fun `leave and delete room cleans up`() {
            val room = registry.createRoom("Temp", ChatRoomType.GROUP, aliceParticipant)
            registry.joinRoom(room.id, bobParticipant)
            registry.leaveRoom(room.id, "bob")
            assertEquals(0, registry.getUserRooms("bob").size)
            registry.deleteRoom(room.id)
            assertNull(registry.getRoom(room.id))
        }

        @Test
        fun `system messages generated for join and leave`() {
            val room = registry.createRoom("General", ChatRoomType.GROUP, aliceParticipant)
            registry.joinRoom(room.id, bobParticipant)
            room.removeParticipant("bob")
            val sysMessages = room.getMessages(100).filter { it.type == MessageType.SYSTEM }
            assertTrue(sysMessages.any { it.content.contains("Alice") && it.content.contains("joined") })
            assertTrue(sysMessages.any { it.content.contains("Bob") && it.content.contains("left") })
        }
    }

    @Nested
    inner class CommandMessagingChatTest {

        private lateinit var store: MessageStore
        private lateinit var executor: CommandExecutor

        @BeforeEach
        fun setup() {
            store = MessageStore()
            executor = CommandExecutor(store)
        }

        @Test
        fun `send message and blank message fails`() {
            val cmd = SendMessageCommand(userId = "alice", roomId = "room-1", content = "Hello!")
            assertTrue(executor.execute(cmd).isSuccess())
            assertEquals(1, store.getMessages("room-1").size)
            assertTrue(executor.execute(SendMessageCommand(userId = "alice", roomId = "room-1", content = "   ")).isFailure())
        }

        @Test
        fun `undo send message soft-deletes it`() {
            executor.execute(SendMessageCommand(userId = "alice", roomId = "room-1", content = "Oops"))
            assertTrue(executor.undo().isSuccess())
            assertEquals(0, store.getMessages("room-1").size)
        }

        @Test
        fun `edit message and undo restores original`() {
            val sendResult = executor.execute(SendMessageCommand(userId = "alice", roomId = "room-1", content = "Typo")) as CommandResult.Success
            val messageId = sendResult.message!!.id
            assertTrue(executor.execute(EditMessageCommand(userId = "alice", roomId = "room-1", messageId = messageId, newContent = "Fixed")).isSuccess())
            assertEquals("Fixed", store.getMessage("room-1", messageId)?.content)
            assertNotNull(store.getMessage("room-1", messageId)?.editedAt)
            executor.undo()
            assertEquals("Typo", store.getMessage("room-1", messageId)?.content)
        }

        @Test
        fun `cannot edit or delete another users message`() {
            val sendResult = executor.execute(SendMessageCommand(userId = "alice", roomId = "room-1", content = "Mine")) as CommandResult.Success
            val messageId = sendResult.message!!.id
            assertTrue(executor.execute(EditMessageCommand(userId = "bob", roomId = "room-1", messageId = messageId, newContent = "Hacked")).isFailure())
            assertTrue(executor.execute(DeleteMessageCommand(userId = "bob", roomId = "room-1", messageId = messageId)).isFailure())
        }

        @Test
        fun `delete and undo delete restores message`() {
            val sendResult = executor.execute(SendMessageCommand(userId = "alice", roomId = "room-1", content = "Restore me")) as CommandResult.Success
            val messageId = sendResult.message!!.id
            executor.execute(DeleteMessageCommand(userId = "alice", roomId = "room-1", messageId = messageId))
            assertEquals(0, store.getMessages("room-1").size)
            executor.undo()
            val restored = store.getMessage("room-1", messageId)
            assertFalse(restored!!.deleted)
            assertEquals("Restore me", restored.content)
        }

        @Test
        fun `react, undo react, and duplicate react fails`() {
            val sendResult = executor.execute(SendMessageCommand(userId = "alice", roomId = "room-1", content = "Like")) as CommandResult.Success
            val messageId = sendResult.message!!.id
            assertTrue(executor.execute(ReactToMessageCommand(userId = "bob", roomId = "room-1", messageId = messageId, emoji = "👍")).isSuccess())
            assertTrue(store.getMessage("room-1", messageId)!!.reactions["👍"]!!.contains("bob"))
            assertTrue(executor.execute(ReactToMessageCommand(userId = "bob", roomId = "room-1", messageId = messageId, emoji = "👍")).isFailure())
            executor.undo()
            assertTrue(store.getMessage("room-1", messageId)!!.reactions.isEmpty())
        }

        @Test
        fun `pin, undo pin, and redo`() {
            val sendResult = executor.execute(SendMessageCommand(userId = "alice", roomId = "room-1", content = "Pin me")) as CommandResult.Success
            val messageId = sendResult.message!!.id
            executor.execute(PinMessageCommand(userId = "alice", roomId = "room-1", messageId = messageId))
            assertTrue(store.getMessage("room-1", messageId)!!.pinned)
            executor.undo()
            assertFalse(store.getMessage("room-1", messageId)!!.pinned)
        }

        @Test
        fun `redo re-executes undone command`() {
            executor.execute(SendMessageCommand(userId = "alice", roomId = "room-1", content = "Redo me"))
            executor.undo()
            assertTrue(executor.redo().isSuccess())
            assertEquals(1, store.getMessages("room-1").size)
        }

        @Test
        fun `command history tracks all operations`() {
            executor.execute(SendMessageCommand(userId = "alice", roomId = "room-1", content = "One"))
            executor.execute(SendMessageCommand(userId = "bob", roomId = "room-1", content = "Two"))
            executor.execute(SendMessageCommand(userId = "alice", roomId = "room-2", content = "Three"))
            assertEquals(3, executor.getHistory().size)
            assertEquals(2, executor.getHistoryForRoom("room-1").size)
            assertEquals(2, executor.getHistoryForUser("alice").size)
        }

        @Test
        fun `offline queue stores and flushes on reconnect`() {
            val queue = OfflineCommandQueue()
            val smart = SmartCommandExecutor(executor, queue)
            smart.disconnect()
            smart.execute(SendMessageCommand(userId = "alice", roomId = "room-1", content = "Msg 1"))
            smart.execute(SendMessageCommand(userId = "alice", roomId = "room-1", content = "Msg 2"))
            assertEquals(0, store.getMessages("room-1").size)
            assertEquals(2, smart.getPendingCount())
            val results = smart.reconnect()
            assertEquals(2, results.size)
            assertTrue(results.all { it.isSuccess() })
            assertEquals(2, store.getMessages("room-1").size)
        }

        @Test
        fun `batch command executes all or rolls back`() {
            val good = SendMessageCommand(userId = "alice", roomId = "room-1", content = "Good")
            assertTrue(executor.execute(BatchCommand(userId = "alice", roomId = "room-1", commands = listOf(good))).isSuccess())
            assertEquals(1, store.getMessages("room-1").size)

            val bad = BatchCommand(userId = "alice", roomId = "room-1", commands = listOf(
                SendMessageCommand(userId = "alice", roomId = "room-1", content = "OK"),
                SendMessageCommand(userId = "alice", roomId = "room-1", content = "")
            ))
            assertTrue(executor.execute(bad).isFailure())
            assertEquals(1, store.getMessages("room-1").size)
        }

        @Test
        fun `cannot edit or react to deleted message`() {
            val sendResult = executor.execute(SendMessageCommand(userId = "alice", roomId = "room-1", content = "Gone")) as CommandResult.Success
            val messageId = sendResult.message!!.id
            executor.execute(DeleteMessageCommand(userId = "alice", roomId = "room-1", messageId = messageId))
            assertTrue(executor.execute(EditMessageCommand(userId = "alice", roomId = "room-1", messageId = messageId, newContent = "Too late")).isFailure())
            assertTrue(executor.execute(ReactToMessageCommand(userId = "bob", roomId = "room-1", messageId = messageId, emoji = "👍")).isFailure())
        }

        @Test
        fun `multiple reactions from different users`() {
            val sendResult = executor.execute(SendMessageCommand(userId = "alice", roomId = "room-1", content = "Popular")) as CommandResult.Success
            val messageId = sendResult.message!!.id
            executor.execute(ReactToMessageCommand(userId = "bob", roomId = "room-1", messageId = messageId, emoji = "👍"))
            executor.execute(ReactToMessageCommand(userId = "charlie", roomId = "room-1", messageId = messageId, emoji = "👍"))
            executor.execute(ReactToMessageCommand(userId = "bob", roomId = "room-1", messageId = messageId, emoji = "❤️"))
            val msg = store.getMessage("room-1", messageId)!!
            assertEquals(setOf("bob", "charlie"), msg.reactions["👍"])
            assertEquals(setOf("bob"), msg.reactions["❤️"])
        }

        @Test
        fun `send message with different types`() {
            executor.execute(SendMessageCommand(userId = "alice", roomId = "room-1", content = "text", messageType = MessageType.TEXT))
            executor.execute(SendMessageCommand(userId = "alice", roomId = "room-1", content = "photo.jpg", messageType = MessageType.IMAGE))
            executor.execute(SendMessageCommand(userId = "alice", roomId = "room-1", content = "doc.pdf", messageType = MessageType.FILE))
            val messages = store.getMessages("room-1")
            assertEquals(MessageType.TEXT, messages[0].type)
            assertEquals(MessageType.IMAGE, messages[1].type)
            assertEquals(MessageType.FILE, messages[2].type)
        }
    }
}
