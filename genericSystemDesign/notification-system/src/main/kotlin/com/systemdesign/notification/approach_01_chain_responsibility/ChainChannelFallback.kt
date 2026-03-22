package com.systemdesign.notification.approach_01_chain_responsibility

import com.systemdesign.notification.common.*
import java.time.LocalDateTime

/**
 * Approach 1: Chain of Responsibility for Channel Fallback
 * 
 * Each handler in the chain attempts delivery through one channel.
 * If delivery fails, the request passes to the next handler.
 * 
 * Pattern: Chain of Responsibility
 * 
 * Trade-offs:
 * + Clear fallback order (push -> email -> SMS -> in-app)
 * + Easy to add/remove channels from chain
 * + Each handler can decide to pass or stop
 * + Handlers are decoupled and testable
 * - Fallback order is fixed at chain construction
 * - Need to rebuild chain to change order
 * 
 * When to use:
 * - When notifications should fall back through multiple channels
 * - When channel availability varies at runtime
 * - When different notification types need different fallback chains
 * 
 * Extensibility:
 * - New channel: Create new handler and insert in chain
 * - Custom fallback logic: Override shouldPassToNext()
 * - Parallel delivery: Create BroadcastHandler variant
 */

/** Abstract base for notification handlers */
abstract class BaseNotificationHandler : NotificationHandler {
    
    protected var nextHandler: NotificationHandler? = null
    
    override fun setNext(handler: NotificationHandler): NotificationHandler {
        nextHandler = handler
        return handler
    }
    
    override fun handle(notification: Notification, preferences: UserPreferences): DeliveryResult? {
        if (!canHandle(notification, preferences)) {
            return passToNext(notification, preferences)
        }
        
        val result = attemptDelivery(notification, preferences)
        
        return if (shouldPassToNext(result)) {
            passToNext(notification, preferences) ?: result
        } else {
            result
        }
    }
    
    protected fun passToNext(
        notification: Notification, 
        preferences: UserPreferences
    ): DeliveryResult? {
        return nextHandler?.handle(notification, preferences)
    }
    
    abstract fun canHandle(notification: Notification, preferences: UserPreferences): Boolean
    abstract fun attemptDelivery(notification: Notification, preferences: UserPreferences): DeliveryResult
    
    protected open fun shouldPassToNext(result: DeliveryResult): Boolean {
        return result.isFailed()
    }
}

/**
 * Push notification handler
 * 
 * Attempts delivery via push notification (FCM, APNS, Web Push)
 */
class PushNotificationHandler(
    private val pushService: PushService
) : BaseNotificationHandler() {
    
    override fun canHandle(notification: Notification, preferences: UserPreferences): Boolean {
        return preferences.isChannelEnabled(NotificationType.PUSH) &&
               preferences.hasValidPushToken() &&
               preferences.canReceiveChannel(NotificationType.PUSH)
    }
    
    override fun attemptDelivery(
        notification: Notification, 
        preferences: UserPreferences
    ): DeliveryResult {
        return try {
            val activeTokens = preferences.pushTokens.filter { it.isValid() }
            
            if (activeTokens.isEmpty()) {
                return DeliveryResult(
                    notificationId = notification.id,
                    channel = NotificationType.PUSH,
                    status = DeliveryStatus.FAILED,
                    failureReason = FailureReason.INVALID_RECIPIENT,
                    errorMessage = "No valid push tokens"
                )
            }
            
            var lastResult: DeliveryResult? = null
            
            for (token in activeTokens) {
                val result = pushService.send(
                    token = token.token,
                    platform = token.platform,
                    title = notification.title,
                    body = notification.body,
                    data = notification.data,
                    imageUrl = notification.imageUrl
                )
                
                if (result.isSuccess()) {
                    return result.copy(notificationId = notification.id)
                }
                lastResult = result.copy(notificationId = notification.id)
            }
            
            lastResult ?: DeliveryResult(
                notificationId = notification.id,
                channel = NotificationType.PUSH,
                status = DeliveryStatus.FAILED,
                failureReason = FailureReason.PROVIDER_ERROR
            )
        } catch (e: Exception) {
            DeliveryResult(
                notificationId = notification.id,
                channel = NotificationType.PUSH,
                status = DeliveryStatus.FAILED,
                failureReason = FailureReason.PROVIDER_ERROR,
                errorMessage = e.message
            )
        }
    }
}

/** Interface for push notification service */
interface PushService {
    fun send(
        token: String,
        platform: PushToken.Platform,
        title: String,
        body: String,
        data: Map<String, Any> = emptyMap(),
        imageUrl: String? = null
    ): DeliveryResult
    
    fun isAvailable(): Boolean
}

/** Mock push service for testing */
class MockPushService(
    private var available: Boolean = true,
    private var shouldSucceed: Boolean = true
) : PushService {
    
    val sentNotifications = mutableListOf<SentPush>()
    
    data class SentPush(
        val token: String,
        val platform: PushToken.Platform,
        val title: String,
        val body: String
    )
    
    override fun send(
        token: String,
        platform: PushToken.Platform,
        title: String,
        body: String,
        data: Map<String, Any>,
        imageUrl: String?
    ): DeliveryResult {
        if (!available) {
            return DeliveryResult(
                notificationId = "",
                channel = NotificationType.PUSH,
                status = DeliveryStatus.FAILED,
                failureReason = FailureReason.CHANNEL_UNAVAILABLE
            )
        }
        
        sentNotifications.add(SentPush(token, platform, title, body))
        
        return if (shouldSucceed) {
            DeliveryResult(
                notificationId = "",
                channel = NotificationType.PUSH,
                status = DeliveryStatus.DELIVERED,
                deliveredAt = LocalDateTime.now()
            )
        } else {
            DeliveryResult(
                notificationId = "",
                channel = NotificationType.PUSH,
                status = DeliveryStatus.FAILED,
                failureReason = FailureReason.PROVIDER_ERROR
            )
        }
    }
    
    override fun isAvailable(): Boolean = available
    
    fun setAvailable(available: Boolean) {
        this.available = available
    }
    
    fun setShouldSucceed(succeed: Boolean) {
        this.shouldSucceed = succeed
    }
}

/**
 * Email notification handler
 * 
 * Attempts delivery via email
 */
class EmailNotificationHandler(
    private val emailService: EmailService
) : BaseNotificationHandler() {
    
    override fun canHandle(notification: Notification, preferences: UserPreferences): Boolean {
        return preferences.isChannelEnabled(NotificationType.EMAIL) &&
               preferences.hasValidEmailAddress() &&
               preferences.canReceiveChannel(NotificationType.EMAIL)
    }
    
    override fun attemptDelivery(
        notification: Notification, 
        preferences: UserPreferences
    ): DeliveryResult {
        val email = preferences.emailAddress
            ?: return DeliveryResult(
                notificationId = notification.id,
                channel = NotificationType.EMAIL,
                status = DeliveryStatus.FAILED,
                failureReason = FailureReason.INVALID_RECIPIENT,
                errorMessage = "No email address configured"
            )
        
        return try {
            emailService.send(
                to = email,
                subject = notification.title,
                body = notification.body,
                isHtml = false
            ).copy(notificationId = notification.id)
        } catch (e: Exception) {
            DeliveryResult(
                notificationId = notification.id,
                channel = NotificationType.EMAIL,
                status = DeliveryStatus.FAILED,
                failureReason = FailureReason.PROVIDER_ERROR,
                errorMessage = e.message
            )
        }
    }
}

/** Interface for email service */
interface EmailService {
    fun send(to: String, subject: String, body: String, isHtml: Boolean = false): DeliveryResult
    fun isAvailable(): Boolean
}

/** Mock email service for testing */
class MockEmailService(
    private var available: Boolean = true,
    private var shouldSucceed: Boolean = true
) : EmailService {
    
    val sentEmails = mutableListOf<SentEmail>()
    
    data class SentEmail(
        val to: String,
        val subject: String,
        val body: String
    )
    
    override fun send(to: String, subject: String, body: String, isHtml: Boolean): DeliveryResult {
        if (!available) {
            return DeliveryResult(
                notificationId = "",
                channel = NotificationType.EMAIL,
                status = DeliveryStatus.FAILED,
                failureReason = FailureReason.CHANNEL_UNAVAILABLE
            )
        }
        
        sentEmails.add(SentEmail(to, subject, body))
        
        return if (shouldSucceed) {
            DeliveryResult(
                notificationId = "",
                channel = NotificationType.EMAIL,
                status = DeliveryStatus.DELIVERED,
                deliveredAt = LocalDateTime.now()
            )
        } else {
            DeliveryResult(
                notificationId = "",
                channel = NotificationType.EMAIL,
                status = DeliveryStatus.FAILED,
                failureReason = FailureReason.PROVIDER_ERROR
            )
        }
    }
    
    override fun isAvailable(): Boolean = available
    
    fun setAvailable(available: Boolean) {
        this.available = available
    }
    
    fun setShouldSucceed(succeed: Boolean) {
        this.shouldSucceed = succeed
    }
}

/**
 * SMS notification handler
 * 
 * Attempts delivery via SMS
 */
class SmsNotificationHandler(
    private val smsService: SmsService
) : BaseNotificationHandler() {
    
    override fun canHandle(notification: Notification, preferences: UserPreferences): Boolean {
        return preferences.isChannelEnabled(NotificationType.SMS) &&
               preferences.hasValidPhoneNumber() &&
               preferences.canReceiveChannel(NotificationType.SMS)
    }
    
    override fun attemptDelivery(
        notification: Notification, 
        preferences: UserPreferences
    ): DeliveryResult {
        val phone = preferences.phoneNumber
            ?: return DeliveryResult(
                notificationId = notification.id,
                channel = NotificationType.SMS,
                status = DeliveryStatus.FAILED,
                failureReason = FailureReason.INVALID_RECIPIENT,
                errorMessage = "No phone number configured"
            )
        
        val message = "${notification.title}: ${notification.body}"
        val truncatedMessage = if (message.length > 160) {
            message.take(157) + "..."
        } else {
            message
        }
        
        return try {
            smsService.send(
                to = phone,
                message = truncatedMessage
            ).copy(notificationId = notification.id)
        } catch (e: Exception) {
            DeliveryResult(
                notificationId = notification.id,
                channel = NotificationType.SMS,
                status = DeliveryStatus.FAILED,
                failureReason = FailureReason.PROVIDER_ERROR,
                errorMessage = e.message
            )
        }
    }
    
    override fun shouldPassToNext(result: DeliveryResult): Boolean {
        return result.isFailed() && result.failureReason != FailureReason.INVALID_RECIPIENT
    }
}

/** Interface for SMS service */
interface SmsService {
    fun send(to: String, message: String): DeliveryResult
    fun isAvailable(): Boolean
}

/** Mock SMS service for testing */
class MockSmsService(
    private var available: Boolean = true,
    private var shouldSucceed: Boolean = true
) : SmsService {
    
    val sentMessages = mutableListOf<SentSms>()
    
    data class SentSms(
        val to: String,
        val message: String
    )
    
    override fun send(to: String, message: String): DeliveryResult {
        if (!available) {
            return DeliveryResult(
                notificationId = "",
                channel = NotificationType.SMS,
                status = DeliveryStatus.FAILED,
                failureReason = FailureReason.CHANNEL_UNAVAILABLE
            )
        }
        
        sentMessages.add(SentSms(to, message))
        
        return if (shouldSucceed) {
            DeliveryResult(
                notificationId = "",
                channel = NotificationType.SMS,
                status = DeliveryStatus.DELIVERED,
                deliveredAt = LocalDateTime.now()
            )
        } else {
            DeliveryResult(
                notificationId = "",
                channel = NotificationType.SMS,
                status = DeliveryStatus.FAILED,
                failureReason = FailureReason.PROVIDER_ERROR
            )
        }
    }
    
    override fun isAvailable(): Boolean = available
    
    fun setAvailable(available: Boolean) {
        this.available = available
    }
    
    fun setShouldSucceed(succeed: Boolean) {
        this.shouldSucceed = succeed
    }
}

/**
 * In-app notification handler
 * 
 * Always available fallback that stores notification for in-app display
 */
class InAppNotificationHandler(
    private val inAppService: InAppNotificationService
) : BaseNotificationHandler() {
    
    override fun canHandle(notification: Notification, preferences: UserPreferences): Boolean {
        return preferences.isChannelEnabled(NotificationType.IN_APP)
    }
    
    override fun attemptDelivery(
        notification: Notification, 
        preferences: UserPreferences
    ): DeliveryResult {
        return try {
            inAppService.store(notification)
            
            DeliveryResult(
                notificationId = notification.id,
                channel = NotificationType.IN_APP,
                status = DeliveryStatus.DELIVERED,
                deliveredAt = LocalDateTime.now()
            )
        } catch (e: Exception) {
            DeliveryResult(
                notificationId = notification.id,
                channel = NotificationType.IN_APP,
                status = DeliveryStatus.FAILED,
                failureReason = FailureReason.PROVIDER_ERROR,
                errorMessage = e.message
            )
        }
    }
    
    override fun shouldPassToNext(result: DeliveryResult): Boolean = false
}

/** Interface for in-app notification service */
interface InAppNotificationService {
    fun store(notification: Notification)
    fun getUnread(userId: String): List<Notification>
    fun markAsRead(notificationId: String)
}

/** Mock in-app notification service */
class MockInAppNotificationService : InAppNotificationService {
    
    private val notifications = mutableMapOf<String, MutableList<Notification>>()
    private val readNotifications = mutableSetOf<String>()
    
    override fun store(notification: Notification) {
        notifications.getOrPut(notification.userId) { mutableListOf() }.add(notification)
    }
    
    override fun getUnread(userId: String): List<Notification> {
        return notifications[userId]
            ?.filter { !readNotifications.contains(it.id) }
            ?: emptyList()
    }
    
    override fun markAsRead(notificationId: String) {
        readNotifications.add(notificationId)
    }
    
    fun getAllStored(): List<Notification> = notifications.values.flatten()
}

/**
 * Builder for constructing notification handler chains
 */
class NotificationChainBuilder {
    
    private val handlers = mutableListOf<BaseNotificationHandler>()
    
    fun addPush(service: PushService): NotificationChainBuilder {
        handlers.add(PushNotificationHandler(service))
        return this
    }
    
    fun addEmail(service: EmailService): NotificationChainBuilder {
        handlers.add(EmailNotificationHandler(service))
        return this
    }
    
    fun addSms(service: SmsService): NotificationChainBuilder {
        handlers.add(SmsNotificationHandler(service))
        return this
    }
    
    fun addInApp(service: InAppNotificationService): NotificationChainBuilder {
        handlers.add(InAppNotificationHandler(service))
        return this
    }
    
    fun build(): NotificationHandler? {
        if (handlers.isEmpty()) return null
        
        for (i in 0 until handlers.size - 1) {
            handlers[i].setNext(handlers[i + 1])
        }
        
        return handlers.first()
    }
}

/**
 * Notification delivery service using chain of responsibility
 */
class ChainNotificationService(
    private val chain: NotificationHandler,
    private val preferencesRepository: UserPreferencesRepository
) {
    private val observers = mutableListOf<NotificationObserver>()
    
    fun addObserver(observer: NotificationObserver) {
        observers.add(observer)
    }
    
    fun send(notification: Notification): DeliveryResult {
        observers.forEach { it.onNotificationCreated(notification) }
        
        val preferences = preferencesRepository.getPreferences(notification.userId)
            ?: return DeliveryResult(
                notificationId = notification.id,
                channel = notification.type,
                status = DeliveryStatus.FAILED,
                failureReason = FailureReason.INVALID_RECIPIENT,
                errorMessage = "User preferences not found"
            )
        
        val result = chain.handle(notification, preferences)
            ?: DeliveryResult(
                notificationId = notification.id,
                channel = notification.type,
                status = DeliveryStatus.FAILED,
                failureReason = FailureReason.CHANNEL_UNAVAILABLE,
                errorMessage = "No channel could handle the notification"
            )
        
        if (result.isSuccess()) {
            observers.forEach { it.onNotificationSent(notification, result) }
        } else {
            observers.forEach { it.onNotificationFailed(notification, result) }
        }
        
        return result
    }
    
    fun sendBatch(batch: NotificationBatch): List<DeliveryResult> {
        return batch.notifications.map { send(it) }
    }
}

/** Repository interface for user preferences */
interface UserPreferencesRepository {
    fun getPreferences(userId: String): UserPreferences?
    fun savePreferences(preferences: UserPreferences)
}

/** In-memory preferences repository */
class InMemoryUserPreferencesRepository : UserPreferencesRepository {
    
    private val preferences = mutableMapOf<String, UserPreferences>()
    
    override fun getPreferences(userId: String): UserPreferences? = preferences[userId]
    
    override fun savePreferences(preferences: UserPreferences) {
        this.preferences[preferences.userId] = preferences
    }
}

/**
 * Priority-aware handler that uses different chains for different priorities
 */
class PriorityAwareHandler(
    private val urgentChain: NotificationHandler,
    private val normalChain: NotificationHandler
) : NotificationHandler {
    
    private var nextHandler: NotificationHandler? = null
    
    override fun setNext(handler: NotificationHandler): NotificationHandler {
        nextHandler = handler
        return handler
    }
    
    override fun handle(notification: Notification, preferences: UserPreferences): DeliveryResult? {
        val chain = if (notification.isUrgent()) urgentChain else normalChain
        return chain.handle(notification, preferences)
    }
}

/**
 * Broadcast handler that sends to multiple channels simultaneously
 */
class BroadcastHandler(
    private val handlers: List<NotificationHandler>
) : NotificationHandler {
    
    private var nextHandler: NotificationHandler? = null
    
    override fun setNext(handler: NotificationHandler): NotificationHandler {
        nextHandler = handler
        return handler
    }
    
    override fun handle(notification: Notification, preferences: UserPreferences): DeliveryResult? {
        val results = handlers.mapNotNull { it.handle(notification, preferences) }
        
        val successfulResult = results.find { it.isSuccess() }
        if (successfulResult != null) {
            return successfulResult
        }
        
        return results.firstOrNull() ?: nextHandler?.handle(notification, preferences)
    }
}
