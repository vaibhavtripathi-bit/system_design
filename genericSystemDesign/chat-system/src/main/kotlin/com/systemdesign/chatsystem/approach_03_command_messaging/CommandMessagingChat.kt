package com.systemdesign.chatsystem.approach_03_command_messaging

import com.systemdesign.chatsystem.common.*
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Approach 3: Command Pattern for Message Operations
 *
 * Every message operation (send, edit, delete, react, pin) is encapsulated as a
 * command object. Commands are executed against a MessageStore, can be undone
 * (unsend, unreact, unpin), queued for offline delivery, and batched for bulk
 * operations.
 *
 * Pattern: Command
 *
 * Trade-offs:
 * + Full undo/redo support for all message operations
 * + Offline queue: commands accumulate while disconnected, flush on reconnect
 * + Audit trail: every mutation is a first-class object with timestamp
 * + Batch operations: apply many commands atomically
 * - Extra object allocation per operation
 * - Undo logic must be maintained per command type
 * - Command ordering matters for correctness (edit after delete = no-op)
 *
 * When to use:
 * - When undo/unsend is a product requirement
 * - When offline-first messaging is needed (queue commands, sync later)
 * - When an audit log of all mutations is valuable
 *
 * Extensibility:
 * - New operation: Implement ChatCommand interface
 * - New undo behavior: Override undo() in command
 * - Middleware: Wrap CommandExecutor to add logging, validation, rate-limiting
 */

interface ChatCommand {
    val commandId: String
    val userId: String
    val roomId: String
    val createdAt: LocalDateTime

    fun execute(store: MessageStore): CommandResult
    fun undo(store: MessageStore): CommandResult
    fun canUndo(): Boolean
}

sealed class CommandResult {
    data class Success(val message: Message? = null, val description: String = "") : CommandResult()
    data class Failure(val reason: String) : CommandResult()

    fun isSuccess(): Boolean = this is Success
    fun isFailure(): Boolean = this is Failure
}

class MessageStore {
    private val messages = ConcurrentHashMap<String, MutableList<Message>>()
    private val deletedSnapshots = ConcurrentHashMap<String, Message>()

    fun addMessage(message: Message) { messages.getOrPut(message.roomId) { mutableListOf() }.add(message) }

    fun getMessage(roomId: String, messageId: String): Message? = messages[roomId]?.find { it.id == messageId }

    fun updateMessage(roomId: String, messageId: String, transform: (Message) -> Message): Message? {
        val roomMessages = messages[roomId] ?: return null
        val index = roomMessages.indexOfFirst { it.id == messageId }
        if (index < 0) return null
        val updated = transform(roomMessages[index])
        roomMessages[index] = updated
        return updated
    }

    fun softDelete(roomId: String, messageId: String): Message? {
        val msg = getMessage(roomId, messageId) ?: return null
        deletedSnapshots[messageId] = msg
        return updateMessage(roomId, messageId) { it.copy(deleted = true, content = "") }
    }

    fun restoreDeleted(messageId: String): Message? {
        val snapshot = deletedSnapshots.remove(messageId) ?: return null
        return updateMessage(snapshot.roomId, messageId) { snapshot.copy(deleted = false) }
    }

    fun getMessages(roomId: String, limit: Int = 50): List<Message> =
        messages[roomId]?.filter { !it.deleted }?.takeLast(limit) ?: emptyList()

    fun getAllMessages(roomId: String): List<Message> = messages[roomId]?.toList() ?: emptyList()
}

class SendMessageCommand(
    override val commandId: String = java.util.UUID.randomUUID().toString(),
    override val userId: String, override val roomId: String,
    private val content: String, private val messageType: MessageType = MessageType.TEXT,
    override val createdAt: LocalDateTime = LocalDateTime.now()
) : ChatCommand {
    private var sentMessageId: String? = null

    override fun execute(store: MessageStore): CommandResult {
        if (content.isBlank()) return CommandResult.Failure("Message content cannot be blank")
        val message = Message(roomId = roomId, senderId = userId, content = content, type = messageType, createdAt = createdAt)
        store.addMessage(message)
        sentMessageId = message.id
        return CommandResult.Success(message, "Message sent")
    }

    override fun undo(store: MessageStore): CommandResult {
        val msgId = sentMessageId ?: return CommandResult.Failure("No message to unsend")
        return CommandResult.Success(store.softDelete(roomId, msgId) ?: return CommandResult.Failure("Message not found"), "Message unsent")
    }

    override fun canUndo(): Boolean = sentMessageId != null
}

class EditMessageCommand(
    override val commandId: String = java.util.UUID.randomUUID().toString(),
    override val userId: String, override val roomId: String,
    private val messageId: String, private val newContent: String,
    override val createdAt: LocalDateTime = LocalDateTime.now()
) : ChatCommand {
    private var previousContent: String? = null

    override fun execute(store: MessageStore): CommandResult {
        val existing = store.getMessage(roomId, messageId) ?: return CommandResult.Failure("Message not found")
        if (existing.senderId != userId) return CommandResult.Failure("Cannot edit another user's message")
        if (existing.deleted) return CommandResult.Failure("Cannot edit a deleted message")
        if (newContent.isBlank()) return CommandResult.Failure("Content cannot be blank")
        previousContent = existing.content
        val updated = store.updateMessage(roomId, messageId) { it.copy(content = newContent, editedAt = LocalDateTime.now()) }
            ?: return CommandResult.Failure("Failed to update message")
        return CommandResult.Success(updated, "Message edited")
    }

    override fun undo(store: MessageStore): CommandResult {
        val prev = previousContent ?: return CommandResult.Failure("No previous content to restore")
        val restored = store.updateMessage(roomId, messageId) { it.copy(content = prev, editedAt = null) }
            ?: return CommandResult.Failure("Message not found")
        return CommandResult.Success(restored, "Edit undone")
    }

    override fun canUndo(): Boolean = previousContent != null
}

class DeleteMessageCommand(
    override val commandId: String = java.util.UUID.randomUUID().toString(),
    override val userId: String, override val roomId: String,
    private val messageId: String,
    override val createdAt: LocalDateTime = LocalDateTime.now()
) : ChatCommand {
    private var wasDeleted = false

    override fun execute(store: MessageStore): CommandResult {
        val existing = store.getMessage(roomId, messageId) ?: return CommandResult.Failure("Message not found")
        if (existing.senderId != userId) return CommandResult.Failure("Cannot delete another user's message")
        if (existing.deleted) return CommandResult.Failure("Message already deleted")
        store.softDelete(roomId, messageId) ?: return CommandResult.Failure("Failed to delete message")
        wasDeleted = true
        return CommandResult.Success(description = "Message deleted")
    }

    override fun undo(store: MessageStore): CommandResult {
        if (!wasDeleted) return CommandResult.Failure("Message was not deleted")
        val restored = store.restoreDeleted(messageId) ?: return CommandResult.Failure("Cannot restore message")
        wasDeleted = false
        return CommandResult.Success(restored, "Message restored")
    }

    override fun canUndo(): Boolean = wasDeleted
}

class ReactToMessageCommand(
    override val commandId: String = java.util.UUID.randomUUID().toString(),
    override val userId: String, override val roomId: String,
    private val messageId: String, private val emoji: String,
    override val createdAt: LocalDateTime = LocalDateTime.now()
) : ChatCommand {
    private var reactionAdded = false

    override fun execute(store: MessageStore): CommandResult {
        val existing = store.getMessage(roomId, messageId) ?: return CommandResult.Failure("Message not found")
        if (existing.deleted) return CommandResult.Failure("Cannot react to a deleted message")
        val currentReactors = existing.reactions[emoji] ?: emptySet()
        if (userId in currentReactors) return CommandResult.Failure("Already reacted with $emoji")
        val updatedReactions = existing.reactions.toMutableMap().apply { this[emoji] = currentReactors + userId }
        val updated = store.updateMessage(roomId, messageId) { it.copy(reactions = updatedReactions) }
            ?: return CommandResult.Failure("Failed to add reaction")
        reactionAdded = true
        return CommandResult.Success(updated, "Reaction added")
    }

    override fun undo(store: MessageStore): CommandResult {
        if (!reactionAdded) return CommandResult.Failure("No reaction to remove")
        val existing = store.getMessage(roomId, messageId) ?: return CommandResult.Failure("Message not found")
        val updatedReactions = existing.reactions.toMutableMap()
        val newReactors = (updatedReactions[emoji] ?: return CommandResult.Failure("Reaction not found")) - userId
        if (newReactors.isEmpty()) updatedReactions.remove(emoji) else updatedReactions[emoji] = newReactors
        val updated = store.updateMessage(roomId, messageId) { it.copy(reactions = updatedReactions) }
            ?: return CommandResult.Failure("Failed to remove reaction")
        reactionAdded = false
        return CommandResult.Success(updated, "Reaction removed")
    }

    override fun canUndo(): Boolean = reactionAdded
}

class PinMessageCommand(
    override val commandId: String = java.util.UUID.randomUUID().toString(),
    override val userId: String, override val roomId: String,
    private val messageId: String,
    override val createdAt: LocalDateTime = LocalDateTime.now()
) : ChatCommand {
    private var wasPinned = false

    override fun execute(store: MessageStore): CommandResult {
        val existing = store.getMessage(roomId, messageId) ?: return CommandResult.Failure("Message not found")
        if (existing.deleted) return CommandResult.Failure("Cannot pin a deleted message")
        if (existing.pinned) return CommandResult.Failure("Message already pinned")
        store.updateMessage(roomId, messageId) { it.copy(pinned = true) } ?: return CommandResult.Failure("Failed to pin message")
        wasPinned = true
        return CommandResult.Success(description = "Message pinned")
    }

    override fun undo(store: MessageStore): CommandResult {
        if (!wasPinned) return CommandResult.Failure("Message was not pinned by this command")
        store.updateMessage(roomId, messageId) { it.copy(pinned = false) } ?: return CommandResult.Failure("Failed to unpin message")
        wasPinned = false
        return CommandResult.Success(description = "Message unpinned")
    }

    override fun canUndo(): Boolean = wasPinned
}

data class CommandRecord(val command: ChatCommand, val result: CommandResult, val executedAt: LocalDateTime = LocalDateTime.now())

class CommandExecutor(private val store: MessageStore) {
    private val history = mutableListOf<CommandRecord>()
    private val undoneCommands = mutableListOf<CommandRecord>()

    fun execute(command: ChatCommand): CommandResult {
        val result = command.execute(store)
        history.add(CommandRecord(command, result))
        if (result.isSuccess()) undoneCommands.clear()
        return result
    }

    fun undo(): CommandResult {
        val lastSuccess = history.lastOrNull { it.result.isSuccess() && it.command.canUndo() }
            ?: return CommandResult.Failure("Nothing to undo")
        val result = lastSuccess.command.undo(store)
        if (result.isSuccess()) { history.remove(lastSuccess); undoneCommands.add(lastSuccess) }
        return result
    }

    fun redo(): CommandResult {
        val lastUndone = undoneCommands.removeLastOrNull() ?: return CommandResult.Failure("Nothing to redo")
        val result = lastUndone.command.execute(store)
        history.add(CommandRecord(lastUndone.command, result))
        return result
    }

    fun getHistory(): List<CommandRecord> = history.toList()
    fun getHistoryForRoom(roomId: String): List<CommandRecord> = history.filter { it.command.roomId == roomId }
    fun getHistoryForUser(userId: String): List<CommandRecord> = history.filter { it.command.userId == userId }
}

class OfflineCommandQueue {
    private val queue = ConcurrentLinkedQueue<ChatCommand>()
    private var online = true

    fun isOnline(): Boolean = online
    fun goOffline() { online = false }
    fun goOnline() { online = true }
    fun enqueue(command: ChatCommand) { queue.add(command) }

    fun flush(executor: CommandExecutor): List<CommandResult> {
        val results = mutableListOf<CommandResult>()
        while (queue.isNotEmpty()) { queue.poll()?.let { results.add(executor.execute(it)) } }
        return results
    }

    fun size(): Int = queue.size
    fun clear() = queue.clear()
}

class SmartCommandExecutor(
    private val executor: CommandExecutor,
    private val offlineQueue: OfflineCommandQueue
) {
    fun execute(command: ChatCommand): CommandResult = if (offlineQueue.isOnline()) {
        executor.execute(command)
    } else {
        offlineQueue.enqueue(command)
        CommandResult.Success(description = "Queued for delivery when online")
    }

    fun reconnect(): List<CommandResult> { offlineQueue.goOnline(); return offlineQueue.flush(executor) }
    fun disconnect() { offlineQueue.goOffline() }
    fun undo(): CommandResult = executor.undo()
    fun redo(): CommandResult = executor.redo()
    fun getPendingCount(): Int = offlineQueue.size()
}

class BatchCommand(
    override val commandId: String = java.util.UUID.randomUUID().toString(),
    override val userId: String,
    override val roomId: String,
    private val commands: List<ChatCommand>,
    override val createdAt: LocalDateTime = LocalDateTime.now()
) : ChatCommand {

    private val executedCommands = mutableListOf<ChatCommand>()

    override fun execute(store: MessageStore): CommandResult {
        executedCommands.clear()
        for (cmd in commands) {
            val result = cmd.execute(store)
            if (result.isFailure()) {
                rollback(store)
                return CommandResult.Failure("Batch failed at command ${cmd.commandId}: ${(result as CommandResult.Failure).reason}")
            }
            executedCommands.add(cmd)
        }
        return CommandResult.Success(description = "Batch of ${commands.size} commands executed")
    }

    override fun undo(store: MessageStore): CommandResult {
        rollback(store)
        return CommandResult.Success(description = "Batch of ${executedCommands.size} commands undone")
    }

    override fun canUndo(): Boolean = executedCommands.isNotEmpty()

    private fun rollback(store: MessageStore) {
        executedCommands.reversed().forEach { cmd ->
            if (cmd.canUndo()) cmd.undo(store)
        }
        executedCommands.clear()
    }
}
