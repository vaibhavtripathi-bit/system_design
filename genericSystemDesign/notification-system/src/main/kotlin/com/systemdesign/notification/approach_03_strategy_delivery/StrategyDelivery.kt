package com.systemdesign.notification.approach_03_strategy_delivery

import com.systemdesign.notification.common.*
import java.time.LocalDateTime

/**
 * Approach 3: Strategy Pattern for Per-Channel Delivery
 * 
 * Each notification channel has its own delivery strategy with channel-specific
 * logic for formatting, sending, and retry handling.
 * 
 * Pattern: Strategy Pattern
 * 
 * Trade-offs:
 * + Each channel's delivery logic is isolated and testable
 * + Easy to add new channels without modifying existing code
 * + Channel-specific optimizations (batching, formatting) are encapsulated
 * + Strategies can be swapped at runtime
 * - Need strategy selection logic
 * - May have some code duplication across strategies
 * 
 * When to use:
 * - When different channels have significantly different delivery mechanisms
 * - When channels need different formatting or batching behavior
 * - When channel providers may be swapped (e.g., different push providers)
 * 
 * Extensibility:
 * - New channel: Implement DeliveryStrategy interface
 * - Provider switch: Create new strategy implementation
 * - Multi-provider: Create composite strategy with failover
 */

/**
 * Push notification delivery strategy
 * 
 * Handles push notification delivery via FCM, APNS, or Web Push
 */
class PushNotificationStrategy(
    private val pushProvider: PushNotificationProvider,
    private val maxRetries: Int = 3,
    private val retryDelayMs: Long = 1000
) : DeliveryStrategy {
    
    override val channelType: NotificationType = NotificationType.PUSH
    
    override fun deliver(notification: Notification, preferences: UserPreferences): DeliveryResult {
        if (!preferences.hasValidPushToken()) {
            return DeliveryResult(
                notificationId = notification.id,
                channel = NotificationType.PUSH,
                status = DeliveryStatus.FAILED,
                failureReason = FailureReason.INVALID_RECIPIENT,
                errorMessage = "No valid push tokens"
            )
        }
        
        val payload = formatPushPayload(notification)
        
        var lastError: String? = null
        var retryCount = 0
        
        for (token in preferences.pushTokens.filter { it.isValid() }) {
            repeat(maxRetries) { attempt ->
                try {
                    val result = pushProvider.sendPush(
                        token = token.token,
                        platform = token.platform,
                        payload = payload
                    )
                    
                    if (result.isSuccess()) {
                        return result.copy(notificationId = notification.id)
                    }
                    
                    lastError = result.errorMessage
                    retryCount = attempt + 1
                    
                    if (attempt < maxRetries - 1) {
                        Thread.sleep(retryDelayMs * (attempt + 1))
                    }
                } catch (e: Exception) {
                    lastError = e.message
                }
            }
        }
        
        return DeliveryResult(
            notificationId = notification.id,
            channel = NotificationType.PUSH,
            status = DeliveryStatus.FAILED,
            failureReason = FailureReason.PROVIDER_ERROR,
            errorMessage = lastError,
            retryCount = retryCount
        )
    }
    
    override fun isAvailable(): Boolean = pushProvider.isHealthy()
    
    private fun formatPushPayload(notification: Notification): PushPayload {
        return PushPayload(
            title = notification.title,
            body = notification.body,
            imageUrl = notification.imageUrl,
            actionUrl = notification.actionUrl,
            data = notification.data + mapOf(
                "notificationId" to notification.id,
                "category" to notification.category.name,
                "priority" to notification.priority.name
            ),
            badge = if (notification.isHighPriority()) 1 else null,
            sound = if (notification.isUrgent()) "urgent.wav" else "default"
        )
    }
}

/** Push payload for sending */
data class PushPayload(
    val title: String,
    val body: String,
    val imageUrl: String?,
    val actionUrl: String?,
    val data: Map<String, Any>,
    val badge: Int?,
    val sound: String
)

/** Push notification provider interface */
interface PushNotificationProvider {
    fun sendPush(
        token: String,
        platform: PushToken.Platform,
        payload: PushPayload
    ): DeliveryResult
    
    fun isHealthy(): Boolean
}

/** Mock push provider for testing */
class MockPushNotificationProvider(
    private var healthy: Boolean = true,
    private var shouldSucceed: Boolean = true
) : PushNotificationProvider {
    
    val deliveredPushes = mutableListOf<DeliveredPush>()
    
    data class DeliveredPush(
        val token: String,
        val platform: PushToken.Platform,
        val payload: PushPayload
    )
    
    override fun sendPush(
        token: String,
        platform: PushToken.Platform,
        payload: PushPayload
    ): DeliveryResult {
        if (!healthy) {
            return DeliveryResult(
                notificationId = "",
                channel = NotificationType.PUSH,
                status = DeliveryStatus.FAILED,
                failureReason = FailureReason.CHANNEL_UNAVAILABLE
            )
        }
        
        deliveredPushes.add(DeliveredPush(token, platform, payload))
        
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
    
    override fun isHealthy(): Boolean = healthy
    
    fun setHealthy(healthy: Boolean) { this.healthy = healthy }
    fun setShouldSucceed(succeed: Boolean) { this.shouldSucceed = succeed }
}

/**
 * Email delivery strategy
 * 
 * Handles email notification delivery with HTML/text formatting
 */
class EmailStrategy(
    private val emailProvider: EmailProvider,
    private val templateEngine: EmailTemplateEngine? = null,
    private val maxRetries: Int = 2
) : DeliveryStrategy {
    
    override val channelType: NotificationType = NotificationType.EMAIL
    
    override fun deliver(notification: Notification, preferences: UserPreferences): DeliveryResult {
        val emailAddress = preferences.emailAddress
            ?: return DeliveryResult(
                notificationId = notification.id,
                channel = NotificationType.EMAIL,
                status = DeliveryStatus.FAILED,
                failureReason = FailureReason.INVALID_RECIPIENT,
                errorMessage = "No email address configured"
            )
        
        val email = formatEmail(notification, emailAddress)
        
        var lastError: String? = null
        
        repeat(maxRetries) { attempt ->
            try {
                val result = emailProvider.sendEmail(email)
                
                if (result.isSuccess()) {
                    return result.copy(notificationId = notification.id)
                }
                
                lastError = result.errorMessage
            } catch (e: Exception) {
                lastError = e.message
            }
        }
        
        return DeliveryResult(
            notificationId = notification.id,
            channel = NotificationType.EMAIL,
            status = DeliveryStatus.FAILED,
            failureReason = FailureReason.PROVIDER_ERROR,
            errorMessage = lastError,
            retryCount = maxRetries
        )
    }
    
    override fun isAvailable(): Boolean = emailProvider.isHealthy()
    
    private fun formatEmail(notification: Notification, toAddress: String): Email {
        val htmlBody = templateEngine?.render(notification) ?: generateDefaultHtml(notification)
        val textBody = notification.body
        
        return Email(
            to = toAddress,
            subject = notification.title,
            htmlBody = htmlBody,
            textBody = textBody,
            headers = mapOf(
                "X-Notification-Id" to notification.id,
                "X-Category" to notification.category.name
            )
        )
    }
    
    private fun generateDefaultHtml(notification: Notification): String {
        return """
            <!DOCTYPE html>
            <html>
            <body>
                <h1>${notification.title}</h1>
                <p>${notification.body}</p>
                ${notification.actionUrl?.let { "<a href=\"$it\">View Details</a>" } ?: ""}
            </body>
            </html>
        """.trimIndent()
    }
}

/** Email data class */
data class Email(
    val to: String,
    val subject: String,
    val htmlBody: String,
    val textBody: String,
    val headers: Map<String, String> = emptyMap()
)

/** Email provider interface */
interface EmailProvider {
    fun sendEmail(email: Email): DeliveryResult
    fun isHealthy(): Boolean
}

/** Email template engine interface */
interface EmailTemplateEngine {
    fun render(notification: Notification): String
}

/** Mock email provider */
class MockEmailProvider(
    private var healthy: Boolean = true,
    private var shouldSucceed: Boolean = true
) : EmailProvider {
    
    val sentEmails = mutableListOf<Email>()
    
    override fun sendEmail(email: Email): DeliveryResult {
        if (!healthy) {
            return DeliveryResult(
                notificationId = "",
                channel = NotificationType.EMAIL,
                status = DeliveryStatus.FAILED,
                failureReason = FailureReason.CHANNEL_UNAVAILABLE
            )
        }
        
        sentEmails.add(email)
        
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
    
    override fun isHealthy(): Boolean = healthy
    
    fun setHealthy(healthy: Boolean) { this.healthy = healthy }
    fun setShouldSucceed(succeed: Boolean) { this.shouldSucceed = succeed }
}

/**
 * SMS delivery strategy
 * 
 * Handles SMS notification delivery with message truncation
 */
class SMSStrategy(
    private val smsProvider: SmsProvider,
    private val maxMessageLength: Int = 160,
    private val allowMultipart: Boolean = false
) : DeliveryStrategy {
    
    override val channelType: NotificationType = NotificationType.SMS
    
    override fun deliver(notification: Notification, preferences: UserPreferences): DeliveryResult {
        val phoneNumber = preferences.phoneNumber
            ?: return DeliveryResult(
                notificationId = notification.id,
                channel = NotificationType.SMS,
                status = DeliveryStatus.FAILED,
                failureReason = FailureReason.INVALID_RECIPIENT,
                errorMessage = "No phone number configured"
            )
        
        val message = formatSmsMessage(notification)
        
        return try {
            val result = smsProvider.sendSms(phoneNumber, message)
            result.copy(notificationId = notification.id)
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
    
    override fun isAvailable(): Boolean = smsProvider.isHealthy()
    
    private fun formatSmsMessage(notification: Notification): String {
        val fullMessage = "${notification.title}: ${notification.body}"
        
        return if (fullMessage.length <= maxMessageLength) {
            fullMessage
        } else if (allowMultipart) {
            fullMessage
        } else {
            fullMessage.take(maxMessageLength - 3) + "..."
        }
    }
}

/** SMS provider interface */
interface SmsProvider {
    fun sendSms(phoneNumber: String, message: String): DeliveryResult
    fun isHealthy(): Boolean
}

/** Mock SMS provider */
class MockSmsProvider(
    private var healthy: Boolean = true,
    private var shouldSucceed: Boolean = true
) : SmsProvider {
    
    val sentMessages = mutableListOf<SentSms>()
    
    data class SentSms(val phoneNumber: String, val message: String)
    
    override fun sendSms(phoneNumber: String, message: String): DeliveryResult {
        if (!healthy) {
            return DeliveryResult(
                notificationId = "",
                channel = NotificationType.SMS,
                status = DeliveryStatus.FAILED,
                failureReason = FailureReason.CHANNEL_UNAVAILABLE
            )
        }
        
        sentMessages.add(SentSms(phoneNumber, message))
        
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
    
    override fun isHealthy(): Boolean = healthy
    
    fun setHealthy(healthy: Boolean) { this.healthy = healthy }
    fun setShouldSucceed(succeed: Boolean) { this.shouldSucceed = succeed }
}

/**
 * In-app notification delivery strategy
 * 
 * Stores notifications for in-app display
 */
class InAppStrategy(
    private val notificationStore: InAppNotificationStore
) : DeliveryStrategy {
    
    override val channelType: NotificationType = NotificationType.IN_APP
    
    override fun deliver(notification: Notification, preferences: UserPreferences): DeliveryResult {
        return try {
            val inAppNotification = InAppNotification(
                id = notification.id,
                userId = notification.userId,
                title = notification.title,
                body = notification.body,
                imageUrl = notification.imageUrl,
                actionUrl = notification.actionUrl,
                category = notification.category,
                priority = notification.priority,
                createdAt = notification.createdAt,
                expiresAt = notification.expiresAt,
                data = notification.data
            )
            
            notificationStore.save(inAppNotification)
            
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
    
    override fun isAvailable(): Boolean = true
}

/** In-app notification data class */
data class InAppNotification(
    val id: String,
    val userId: String,
    val title: String,
    val body: String,
    val imageUrl: String?,
    val actionUrl: String?,
    val category: NotificationCategory,
    val priority: NotificationPriority,
    val createdAt: LocalDateTime,
    val expiresAt: LocalDateTime?,
    val data: Map<String, Any>,
    val read: Boolean = false,
    val readAt: LocalDateTime? = null
)

/** In-app notification store interface */
interface InAppNotificationStore {
    fun save(notification: InAppNotification)
    fun getUnread(userId: String): List<InAppNotification>
    fun markAsRead(notificationId: String)
    fun delete(notificationId: String)
}

/** In-memory in-app store */
class InMemoryInAppNotificationStore : InAppNotificationStore {
    
    private val notifications = mutableMapOf<String, InAppNotification>()
    
    override fun save(notification: InAppNotification) {
        notifications[notification.id] = notification
    }
    
    override fun getUnread(userId: String): List<InAppNotification> {
        return notifications.values
            .filter { it.userId == userId && !it.read }
            .sortedByDescending { it.createdAt }
    }
    
    override fun markAsRead(notificationId: String) {
        notifications[notificationId]?.let {
            notifications[notificationId] = it.copy(
                read = true,
                readAt = LocalDateTime.now()
            )
        }
    }
    
    override fun delete(notificationId: String) {
        notifications.remove(notificationId)
    }
    
    fun getAll(): List<InAppNotification> = notifications.values.toList()
}

/**
 * Strategy selector for choosing delivery strategy
 */
class DeliveryStrategySelector(
    private val strategies: Map<NotificationType, DeliveryStrategy>
) {
    fun selectStrategy(notification: Notification): DeliveryStrategy? {
        return strategies[notification.type]
    }
    
    fun selectAvailableStrategy(preferredType: NotificationType): DeliveryStrategy? {
        return strategies[preferredType]?.takeIf { it.isAvailable() }
            ?: strategies.values.firstOrNull { it.isAvailable() }
    }
    
    fun getAvailableStrategies(): List<DeliveryStrategy> {
        return strategies.values.filter { it.isAvailable() }
    }
}

/**
 * Composite strategy that tries multiple strategies with failover
 */
class FailoverDeliveryStrategy(
    private val primaryStrategy: DeliveryStrategy,
    private val fallbackStrategies: List<DeliveryStrategy>
) : DeliveryStrategy {
    
    override val channelType: NotificationType = primaryStrategy.channelType
    
    override fun deliver(notification: Notification, preferences: UserPreferences): DeliveryResult {
        if (primaryStrategy.isAvailable()) {
            val result = primaryStrategy.deliver(notification, preferences)
            if (result.isSuccess()) {
                return result
            }
        }
        
        for (fallback in fallbackStrategies) {
            if (fallback.isAvailable() && preferences.canReceiveChannel(fallback.channelType)) {
                val result = fallback.deliver(
                    notification.withType(fallback.channelType),
                    preferences
                )
                if (result.isSuccess()) {
                    return result
                }
            }
        }
        
        return DeliveryResult(
            notificationId = notification.id,
            channel = primaryStrategy.channelType,
            status = DeliveryStatus.FAILED,
            failureReason = FailureReason.CHANNEL_UNAVAILABLE,
            errorMessage = "All delivery channels exhausted"
        )
    }
    
    override fun isAvailable(): Boolean {
        return primaryStrategy.isAvailable() || fallbackStrategies.any { it.isAvailable() }
    }
}

/**
 * Notification delivery service using strategies
 */
class StrategyDeliveryService(
    private val selector: DeliveryStrategySelector,
    private val preferencesRepository: com.systemdesign.notification.approach_01_chain_responsibility.UserPreferencesRepository
) {
    private val observers = mutableListOf<NotificationObserver>()
    
    fun addObserver(observer: NotificationObserver) {
        observers.add(observer)
    }
    
    fun deliver(notification: Notification): DeliveryResult {
        observers.forEach { it.onNotificationCreated(notification) }
        
        val preferences = preferencesRepository.getPreferences(notification.userId)
            ?: return DeliveryResult(
                notificationId = notification.id,
                channel = notification.type,
                status = DeliveryStatus.FAILED,
                failureReason = FailureReason.INVALID_RECIPIENT,
                errorMessage = "User preferences not found"
            )
        
        val strategy = selector.selectStrategy(notification)
            ?: return DeliveryResult(
                notificationId = notification.id,
                channel = notification.type,
                status = DeliveryStatus.FAILED,
                failureReason = FailureReason.CHANNEL_UNAVAILABLE,
                errorMessage = "No strategy available for channel ${notification.type}"
            )
        
        val result = strategy.deliver(notification, preferences)
        
        if (result.isSuccess()) {
            observers.forEach { it.onNotificationSent(notification, result) }
        } else {
            observers.forEach { it.onNotificationFailed(notification, result) }
        }
        
        return result
    }
    
    fun deliverWithFallback(
        notification: Notification,
        fallbackOrder: List<NotificationType>
    ): DeliveryResult {
        val preferences = preferencesRepository.getPreferences(notification.userId)
            ?: return DeliveryResult(
                notificationId = notification.id,
                channel = notification.type,
                status = DeliveryStatus.FAILED,
                failureReason = FailureReason.INVALID_RECIPIENT
            )
        
        for (channelType in listOf(notification.type) + fallbackOrder) {
            if (!preferences.canReceiveChannel(channelType)) continue
            
            val strategy = selector.selectStrategy(notification.withType(channelType))
            if (strategy != null && strategy.isAvailable()) {
                val result = strategy.deliver(notification.withType(channelType), preferences)
                if (result.isSuccess()) {
                    return result
                }
            }
        }
        
        return DeliveryResult(
            notificationId = notification.id,
            channel = notification.type,
            status = DeliveryStatus.FAILED,
            failureReason = FailureReason.CHANNEL_UNAVAILABLE
        )
    }
    
    fun getAvailableChannels(): List<NotificationType> {
        return selector.getAvailableStrategies().map { it.channelType }
    }
}
