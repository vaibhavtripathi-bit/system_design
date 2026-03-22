/**
 * # Approach 01: Simple Push Notification Handler
 *
 * ## Pattern Used
 * Basic notification handling with type-based routing.
 *
 * ## Trade-offs
 * - **Pros:** Simple, easy to understand
 * - **Cons:** No persistence, no deduplication
 */
package com.systemdesign.pushnotification.approach_01_simple

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

data class PushNotification(
    val id: String,
    val type: String,
    val title: String,
    val body: String,
    val data: Map<String, Any> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)

interface NotificationHandler {
    fun canHandle(type: String): Boolean
    fun handle(notification: PushNotification)
}

class SimplePushHandler {
    private val handlers = CopyOnWriteArrayList<NotificationHandler>()
    private val defaultHandler: NotificationHandler? = null

    fun registerHandler(handler: NotificationHandler) {
        handlers.add(handler)
    }

    fun unregisterHandler(handler: NotificationHandler) {
        handlers.remove(handler)
    }

    fun handleNotification(notification: PushNotification) {
        val handler = handlers.find { it.canHandle(notification.type) }
            ?: defaultHandler
        handler?.handle(notification)
    }

    fun setDefaultHandler(handler: NotificationHandler) {
        handlers.add(0, handler)
    }

    fun clear() = handlers.clear()
}

class TypedNotificationHandler(
    private val type: String,
    private val action: (PushNotification) -> Unit
) : NotificationHandler {
    override fun canHandle(type: String) = this.type == type
    override fun handle(notification: PushNotification) = action(notification)
}
