package com.systemdesign.notification.common

import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

/**
 * Core domain models for Notification System.
 * 
 * Extensibility Points:
 * - New channels: Add to NotificationType enum and implement DeliveryStrategy
 * - New priorities: Add to NotificationPriority enum
 * - New processing: Implement NotificationProcessor decorator
 * - New delivery rules: Add to UserPreferences
 * 
 * Breaking Changes Required For:
 * - Changing notification ID structure
 * - Modifying channel behavior fundamentally
 */

/** Types of notification channels */
enum class NotificationType {
    PUSH,
    EMAIL,
    SMS,
    IN_APP
}

/** Priority levels for notifications */
enum class NotificationPriority {
    LOW,
    NORMAL,
    HIGH,
    URGENT
}

/** Delivery status of a notification */
enum class DeliveryStatus {
    PENDING,
    QUEUED,
    SENDING,
    DELIVERED,
    FAILED,
    BOUNCED,
    REJECTED,
    RATE_LIMITED,
    SUPPRESSED
}

/** Reason for delivery failure */
enum class FailureReason {
    INVALID_RECIPIENT,
    CHANNEL_UNAVAILABLE,
    RATE_LIMITED,
    DND_ACTIVE,
    USER_OPTED_OUT,
    DUPLICATE_SUPPRESSED,
    TIMEOUT,
    PROVIDER_ERROR,
    UNKNOWN
}

/** Category of notification for filtering */
enum class NotificationCategory {
    TRANSACTIONAL,
    MARKETING,
    SOCIAL,
    SECURITY,
    SYSTEM,
    REMINDER,
    ALERT
}

/** Represents a notification to be sent */
data class Notification(
    val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val title: String,
    val body: String,
    val type: NotificationType,
    val priority: NotificationPriority = NotificationPriority.NORMAL,
    val category: NotificationCategory = NotificationCategory.SYSTEM,
    val data: Map<String, Any> = emptyMap(),
    val imageUrl: String? = null,
    val actionUrl: String? = null,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val expiresAt: LocalDateTime? = null,
    val groupKey: String? = null,
    val deduplicationKey: String? = null
) {
    fun isExpired(): Boolean = expiresAt?.isBefore(LocalDateTime.now()) == true
    
    fun isUrgent(): Boolean = priority == NotificationPriority.URGENT
    
    fun isHighPriority(): Boolean = priority in listOf(
        NotificationPriority.HIGH, 
        NotificationPriority.URGENT
    )
    
    fun withPriority(newPriority: NotificationPriority): Notification = 
        copy(priority = newPriority)
    
    fun withType(newType: NotificationType): Notification = 
        copy(type = newType)
}

/** Result of attempting to deliver a notification */
data class DeliveryResult(
    val notificationId: String,
    val channel: NotificationType,
    val status: DeliveryStatus,
    val deliveredAt: LocalDateTime? = null,
    val failureReason: FailureReason? = null,
    val errorMessage: String? = null,
    val providerResponse: String? = null,
    val retryCount: Int = 0,
    val nextRetryAt: LocalDateTime? = null
) {
    fun isSuccess(): Boolean = status == DeliveryStatus.DELIVERED
    fun isFailed(): Boolean = status in listOf(
        DeliveryStatus.FAILED, 
        DeliveryStatus.BOUNCED,
        DeliveryStatus.REJECTED
    )
    fun canRetry(): Boolean = !isSuccess() && retryCount < 3 && nextRetryAt != null
}

/** User notification preferences */
data class UserPreferences(
    val userId: String,
    val enabledChannels: Set<NotificationType> = NotificationType.entries.toSet(),
    val categoryPreferences: Map<NotificationCategory, Set<NotificationType>> = emptyMap(),
    val quietHours: QuietHours? = null,
    val timezone: String = "UTC",
    val emailAddress: String? = null,
    val phoneNumber: String? = null,
    val pushTokens: List<PushToken> = emptyList(),
    val maxNotificationsPerHour: Int = 100,
    val groupSimilarNotifications: Boolean = true,
    val digestEnabled: Boolean = false,
    val digestSchedule: DigestSchedule? = null
) {
    fun isChannelEnabled(channel: NotificationType): Boolean = 
        enabledChannels.contains(channel)
    
    fun getPreferredChannels(category: NotificationCategory): Set<NotificationType> {
        return categoryPreferences[category] ?: enabledChannels
    }
    
    fun hasValidPushToken(): Boolean = pushTokens.any { it.isValid() }
    
    fun hasValidEmailAddress(): Boolean = emailAddress?.contains("@") == true
    
    fun hasValidPhoneNumber(): Boolean = phoneNumber?.length?.let { it >= 10 } == true
    
    fun canReceiveChannel(channel: NotificationType): Boolean {
        return when (channel) {
            NotificationType.PUSH -> hasValidPushToken()
            NotificationType.EMAIL -> hasValidEmailAddress()
            NotificationType.SMS -> hasValidPhoneNumber()
            NotificationType.IN_APP -> true
        }
    }
}

/** Push notification token */
data class PushToken(
    val token: String,
    val platform: Platform,
    val deviceId: String,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val lastUsedAt: LocalDateTime = LocalDateTime.now()
) {
    enum class Platform { IOS, ANDROID, WEB }
    
    fun isValid(): Boolean {
        val maxAge = LocalDateTime.now().minusDays(90)
        return lastUsedAt.isAfter(maxAge)
    }
}

/** Quiet/Do-Not-Disturb hours configuration */
data class QuietHours(
    val enabled: Boolean = false,
    val startTime: LocalTime = LocalTime.of(22, 0),
    val endTime: LocalTime = LocalTime.of(8, 0),
    val daysOfWeek: Set<java.time.DayOfWeek> = java.time.DayOfWeek.entries.toSet(),
    val allowUrgent: Boolean = true,
    val allowCategories: Set<NotificationCategory> = setOf(NotificationCategory.SECURITY)
) {
    fun isActive(currentTime: LocalTime, dayOfWeek: java.time.DayOfWeek): Boolean {
        if (!enabled) return false
        if (!daysOfWeek.contains(dayOfWeek)) return false
        
        return if (startTime < endTime) {
            currentTime >= startTime && currentTime < endTime
        } else {
            currentTime >= startTime || currentTime < endTime
        }
    }
    
    fun shouldAllow(notification: Notification): Boolean {
        if (notification.isUrgent() && allowUrgent) return true
        if (allowCategories.contains(notification.category)) return true
        return false
    }
}

/** Digest schedule configuration */
data class DigestSchedule(
    val frequency: DigestFrequency,
    val deliveryTime: LocalTime = LocalTime.of(9, 0),
    val daysOfWeek: Set<java.time.DayOfWeek> = setOf(
        java.time.DayOfWeek.MONDAY,
        java.time.DayOfWeek.WEDNESDAY,
        java.time.DayOfWeek.FRIDAY
    )
) {
    enum class DigestFrequency { DAILY, WEEKLY, CUSTOM }
}

/** Rate limit configuration */
data class RateLimitConfig(
    val maxPerMinute: Int = 10,
    val maxPerHour: Int = 100,
    val maxPerDay: Int = 1000,
    val burstAllowance: Int = 5
)

/** Notification template for reusable content */
data class NotificationTemplate(
    val id: String,
    val name: String,
    val titleTemplate: String,
    val bodyTemplate: String,
    val category: NotificationCategory,
    val defaultPriority: NotificationPriority = NotificationPriority.NORMAL,
    val supportedChannels: Set<NotificationType> = NotificationType.entries.toSet()
) {
    fun render(variables: Map<String, String>): Pair<String, String> {
        var title = titleTemplate
        var body = bodyTemplate
        
        variables.forEach { (key, value) ->
            title = title.replace("{{$key}}", value)
            body = body.replace("{{$key}}", value)
        }
        
        return title to body
    }
}

/** Observer interface for notification events */
interface NotificationObserver {
    fun onNotificationCreated(notification: Notification)
    fun onNotificationSent(notification: Notification, result: DeliveryResult)
    fun onNotificationFailed(notification: Notification, result: DeliveryResult)
}

/** Strategy interface for notification delivery */
interface DeliveryStrategy {
    val channelType: NotificationType
    
    fun deliver(notification: Notification, preferences: UserPreferences): DeliveryResult
    fun isAvailable(): Boolean
}

/** Handler interface for chain of responsibility */
interface NotificationHandler {
    fun setNext(handler: NotificationHandler): NotificationHandler
    fun handle(notification: Notification, preferences: UserPreferences): DeliveryResult?
}

/** Processor interface for decorator pattern */
interface NotificationProcessor {
    fun process(notification: Notification, preferences: UserPreferences): ProcessingResult
}

/** Result of notification processing */
sealed class ProcessingResult {
    data class Continue(val notification: Notification) : ProcessingResult()
    data class Suppress(val reason: String) : ProcessingResult()
    data class Delay(val notification: Notification, val until: LocalDateTime) : ProcessingResult()
    data class Transform(val notification: Notification) : ProcessingResult()
}

/** Notification batch for bulk operations */
data class NotificationBatch(
    val id: String = UUID.randomUUID().toString(),
    val notifications: List<Notification>,
    val createdAt: LocalDateTime = LocalDateTime.now()
) {
    val size: Int get() = notifications.size
}

/** Delivery statistics */
data class DeliveryStats(
    val totalSent: Int = 0,
    val delivered: Int = 0,
    val failed: Int = 0,
    val pending: Int = 0,
    val byChannel: Map<NotificationType, ChannelStats> = emptyMap()
) {
    val deliveryRate: Double 
        get() = if (totalSent > 0) delivered.toDouble() / totalSent else 0.0
}

/** Per-channel statistics */
data class ChannelStats(
    val sent: Int = 0,
    val delivered: Int = 0,
    val failed: Int = 0,
    val averageLatencyMs: Long = 0
)

/** Notification event for tracking */
sealed class NotificationEvent {
    abstract val notificationId: String
    abstract val timestamp: LocalDateTime
    
    data class Created(
        override val notificationId: String,
        val notification: Notification,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : NotificationEvent()
    
    data class Queued(
        override val notificationId: String,
        val channel: NotificationType,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : NotificationEvent()
    
    data class Sent(
        override val notificationId: String,
        val channel: NotificationType,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : NotificationEvent()
    
    data class Delivered(
        override val notificationId: String,
        val channel: NotificationType,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : NotificationEvent()
    
    data class Failed(
        override val notificationId: String,
        val channel: NotificationType,
        val reason: FailureReason,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : NotificationEvent()
    
    data class Opened(
        override val notificationId: String,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : NotificationEvent()
    
    data class Clicked(
        override val notificationId: String,
        val actionUrl: String?,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : NotificationEvent()
    
    data class Dismissed(
        override val notificationId: String,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : NotificationEvent()
}
