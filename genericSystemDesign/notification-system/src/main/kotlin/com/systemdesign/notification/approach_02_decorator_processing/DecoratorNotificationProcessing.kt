package com.systemdesign.notification.approach_02_decorator_processing

import com.systemdesign.notification.common.*
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Approach 2: Decorator Pattern for Notification Processing
 * 
 * Processing decorators can be stacked to add validation, filtering,
 * and transformation logic before delivery.
 * 
 * Pattern: Decorator Pattern
 * 
 * Trade-offs:
 * + Processing rules are composable and stackable
 * + Easy to add/remove processing steps
 * + Each processor is testable in isolation
 * + Order of processors matters and is explicit
 * - Can add latency if many processors
 * - Need to track which processors suppressed notifications
 * 
 * When to use:
 * - When notifications need multiple pre-delivery checks
 * - When processing rules vary by user or notification type
 * - When building configurable notification pipelines
 * 
 * Extensibility:
 * - New processor: Implement NotificationProcessorDecorator
 * - Conditional processing: Use ConditionalProcessor wrapper
 * - Analytics: Create MetricsDecorator to track processing
 */

/** Base processor that passes notifications through unchanged */
class BaseProcessor : NotificationProcessor {
    override fun process(notification: Notification, preferences: UserPreferences): ProcessingResult {
        return ProcessingResult.Continue(notification)
    }
}

/** Abstract decorator for notification processing */
abstract class NotificationProcessorDecorator(
    protected val wrapped: NotificationProcessor
) : NotificationProcessor

/**
 * Priority decorator - handles urgent notifications first
 * 
 * Ensures urgent notifications bypass certain checks and get expedited processing
 */
class PriorityDecorator(
    wrapped: NotificationProcessor,
    private val urgentBypassProcessing: Boolean = true
) : NotificationProcessorDecorator(wrapped) {
    
    override fun process(notification: Notification, preferences: UserPreferences): ProcessingResult {
        if (notification.isUrgent() && urgentBypassProcessing) {
            return ProcessingResult.Continue(notification)
        }
        
        return wrapped.process(notification, preferences)
    }
}

/**
 * Do-Not-Disturb decorator - respects user quiet hours
 * 
 * Suppresses or delays notifications during DND periods
 */
class DNDDecorator(
    wrapped: NotificationProcessor,
    private val timeProvider: () -> LocalDateTime = { LocalDateTime.now() }
) : NotificationProcessorDecorator(wrapped) {
    
    override fun process(notification: Notification, preferences: UserPreferences): ProcessingResult {
        val quietHours = preferences.quietHours
        
        if (quietHours == null || !quietHours.enabled) {
            return wrapped.process(notification, preferences)
        }
        
        val now = timeProvider()
        val currentTime = now.toLocalTime()
        val dayOfWeek = now.dayOfWeek
        
        if (!quietHours.isActive(currentTime, dayOfWeek)) {
            return wrapped.process(notification, preferences)
        }
        
        if (quietHours.shouldAllow(notification)) {
            return wrapped.process(notification, preferences)
        }
        
        val resumeTime = calculateResumeTime(quietHours, now)
        
        return ProcessingResult.Delay(
            notification = notification,
            until = resumeTime
        )
    }
    
    private fun calculateResumeTime(quietHours: QuietHours, now: LocalDateTime): LocalDateTime {
        val endTime = quietHours.endTime
        
        return if (now.toLocalTime() < endTime) {
            now.withHour(endTime.hour).withMinute(endTime.minute).withSecond(0)
        } else {
            now.plusDays(1).withHour(endTime.hour).withMinute(endTime.minute).withSecond(0)
        }
    }
}

/**
 * Deduplication decorator - suppresses duplicate notifications
 * 
 * Prevents the same notification from being sent multiple times within a window
 */
class DeduplicationDecorator(
    wrapped: NotificationProcessor,
    private val windowMinutes: Int = 60,
    private val timeProvider: () -> LocalDateTime = { LocalDateTime.now() }
) : NotificationProcessorDecorator(wrapped) {
    
    private val recentNotifications = ConcurrentHashMap<String, LocalDateTime>()
    
    override fun process(notification: Notification, preferences: UserPreferences): ProcessingResult {
        cleanExpiredEntries()
        
        val dedupeKey = notification.deduplicationKey 
            ?: generateDedupeKey(notification)
        
        val lastSent = recentNotifications[dedupeKey]
        val now = timeProvider()
        
        if (lastSent != null && lastSent.plusMinutes(windowMinutes.toLong()).isAfter(now)) {
            return ProcessingResult.Suppress("Duplicate notification within $windowMinutes minute window")
        }
        
        val result = wrapped.process(notification, preferences)
        
        if (result is ProcessingResult.Continue) {
            recentNotifications[dedupeKey] = now
        }
        
        return result
    }
    
    private fun generateDedupeKey(notification: Notification): String {
        return "${notification.userId}:${notification.type}:${notification.title.hashCode()}"
    }
    
    private fun cleanExpiredEntries() {
        val now = timeProvider()
        val cutoff = now.minusMinutes(windowMinutes.toLong())
        
        recentNotifications.entries.removeIf { it.value.isBefore(cutoff) }
    }
    
    fun getRecentCount(): Int = recentNotifications.size
    
    fun clearCache() {
        recentNotifications.clear()
    }
}

/**
 * Rate limiting decorator - prevents notification spam
 * 
 * Limits the number of notifications per time window per user
 */
class RateLimitDecorator(
    wrapped: NotificationProcessor,
    private val config: RateLimitConfig = RateLimitConfig(),
    private val timeProvider: () -> LocalDateTime = { LocalDateTime.now() }
) : NotificationProcessorDecorator(wrapped) {
    
    private val userCounts = ConcurrentHashMap<String, UserRateLimit>()
    
    private data class UserRateLimit(
        val minuteCount: Int,
        val hourCount: Int,
        val dayCount: Int,
        val minuteStart: LocalDateTime,
        val hourStart: LocalDateTime,
        val dayStart: LocalDateTime
    )
    
    override fun process(notification: Notification, preferences: UserPreferences): ProcessingResult {
        if (notification.isUrgent()) {
            return wrapped.process(notification, preferences)
        }
        
        val userId = notification.userId
        val now = timeProvider()
        
        val limits = getOrCreateLimits(userId, now)
        val updatedLimits = updateLimits(limits, now)
        
        if (!isWithinLimits(updatedLimits)) {
            return ProcessingResult.Suppress("Rate limit exceeded for user $userId")
        }
        
        val result = wrapped.process(notification, preferences)
        
        if (result is ProcessingResult.Continue) {
            incrementCounts(userId, updatedLimits)
        }
        
        return result
    }
    
    private fun getOrCreateLimits(userId: String, now: LocalDateTime): UserRateLimit {
        return userCounts.getOrPut(userId) {
            UserRateLimit(
                minuteCount = 0,
                hourCount = 0,
                dayCount = 0,
                minuteStart = now,
                hourStart = now,
                dayStart = now
            )
        }
    }
    
    private fun updateLimits(limits: UserRateLimit, now: LocalDateTime): UserRateLimit {
        var updated = limits
        
        if (now.isAfter(limits.minuteStart.plusMinutes(1))) {
            updated = updated.copy(minuteCount = 0, minuteStart = now)
        }
        
        if (now.isAfter(limits.hourStart.plusHours(1))) {
            updated = updated.copy(hourCount = 0, hourStart = now)
        }
        
        if (now.isAfter(limits.dayStart.plusDays(1))) {
            updated = updated.copy(dayCount = 0, dayStart = now)
        }
        
        return updated
    }
    
    private fun isWithinLimits(limits: UserRateLimit): Boolean {
        return limits.minuteCount < config.maxPerMinute &&
               limits.hourCount < config.maxPerHour &&
               limits.dayCount < config.maxPerDay
    }
    
    private fun incrementCounts(userId: String, limits: UserRateLimit) {
        userCounts[userId] = limits.copy(
            minuteCount = limits.minuteCount + 1,
            hourCount = limits.hourCount + 1,
            dayCount = limits.dayCount + 1
        )
    }
    
    fun getUserStats(userId: String): Map<String, Int>? {
        return userCounts[userId]?.let {
            mapOf(
                "minuteCount" to it.minuteCount,
                "hourCount" to it.hourCount,
                "dayCount" to it.dayCount
            )
        }
    }
    
    fun clearStats() {
        userCounts.clear()
    }
}

/**
 * Category filter decorator - filters by notification category preferences
 */
class CategoryFilterDecorator(
    wrapped: NotificationProcessor
) : NotificationProcessorDecorator(wrapped) {
    
    override fun process(notification: Notification, preferences: UserPreferences): ProcessingResult {
        val preferredChannels = preferences.getPreferredChannels(notification.category)
        
        if (!preferredChannels.contains(notification.type)) {
            val altChannels = preferredChannels.toList()
            
            return if (altChannels.isNotEmpty()) {
                val altNotification = notification.withType(altChannels.first())
                wrapped.process(altNotification, preferences)
            } else {
                ProcessingResult.Suppress("Category ${notification.category} not enabled for any channel")
            }
        }
        
        return wrapped.process(notification, preferences)
    }
}

/**
 * Content validation decorator - validates notification content
 */
class ContentValidationDecorator(
    wrapped: NotificationProcessor,
    private val maxTitleLength: Int = 100,
    private val maxBodyLength: Int = 500
) : NotificationProcessorDecorator(wrapped) {
    
    override fun process(notification: Notification, preferences: UserPreferences): ProcessingResult {
        if (notification.title.isBlank()) {
            return ProcessingResult.Suppress("Notification title cannot be empty")
        }
        
        if (notification.body.isBlank()) {
            return ProcessingResult.Suppress("Notification body cannot be empty")
        }
        
        val truncatedNotification = if (notification.title.length > maxTitleLength || 
                                        notification.body.length > maxBodyLength) {
            notification.copy(
                title = notification.title.take(maxTitleLength),
                body = notification.body.take(maxBodyLength)
            )
        } else {
            notification
        }
        
        return wrapped.process(truncatedNotification, preferences)
    }
}

/**
 * Expiration check decorator - filters expired notifications
 */
class ExpirationDecorator(
    wrapped: NotificationProcessor,
    private val timeProvider: () -> LocalDateTime = { LocalDateTime.now() }
) : NotificationProcessorDecorator(wrapped) {
    
    override fun process(notification: Notification, preferences: UserPreferences): ProcessingResult {
        if (notification.isExpired()) {
            return ProcessingResult.Suppress("Notification has expired")
        }
        
        return wrapped.process(notification, preferences)
    }
}

/**
 * Grouping decorator - groups similar notifications
 */
class GroupingDecorator(
    wrapped: NotificationProcessor,
    private val maxGroupSize: Int = 5,
    private val groupWindowMinutes: Int = 5,
    private val timeProvider: () -> LocalDateTime = { LocalDateTime.now() }
) : NotificationProcessorDecorator(wrapped) {
    
    private val pendingGroups = ConcurrentHashMap<String, MutableList<Notification>>()
    private val groupTimestamps = ConcurrentHashMap<String, LocalDateTime>()
    
    override fun process(notification: Notification, preferences: UserPreferences): ProcessingResult {
        if (!preferences.groupSimilarNotifications) {
            return wrapped.process(notification, preferences)
        }
        
        val groupKey = notification.groupKey ?: return wrapped.process(notification, preferences)
        val fullKey = "${notification.userId}:$groupKey"
        
        val now = timeProvider()
        val groupStart = groupTimestamps[fullKey]
        
        if (groupStart != null && groupStart.plusMinutes(groupWindowMinutes.toLong()).isBefore(now)) {
            flushGroup(fullKey, preferences)
        }
        
        val group = pendingGroups.getOrPut(fullKey) { mutableListOf() }
        
        if (group.isEmpty()) {
            groupTimestamps[fullKey] = now
        }
        
        group.add(notification)
        
        if (group.size >= maxGroupSize) {
            return flushGroup(fullKey, preferences)
        }
        
        return ProcessingResult.Suppress("Notification grouped for batch delivery")
    }
    
    private fun flushGroup(key: String, preferences: UserPreferences): ProcessingResult {
        val group = pendingGroups.remove(key) ?: return ProcessingResult.Suppress("No group to flush")
        groupTimestamps.remove(key)
        
        if (group.isEmpty()) {
            return ProcessingResult.Suppress("Empty group")
        }
        
        val summary = createGroupSummary(group)
        return wrapped.process(summary, preferences)
    }
    
    private fun createGroupSummary(notifications: List<Notification>): Notification {
        val first = notifications.first()
        val count = notifications.size
        
        return first.copy(
            id = java.util.UUID.randomUUID().toString(),
            title = "${first.title} (+${count - 1} more)",
            body = "You have $count new ${first.category.name.lowercase()} notifications",
            data = first.data + mapOf("groupedCount" to count)
        )
    }
    
    fun flushAllGroups(preferences: UserPreferences): List<ProcessingResult> {
        val keys = pendingGroups.keys.toList()
        return keys.map { flushGroup(it, preferences) }
    }
    
    fun getPendingGroupCount(): Int = pendingGroups.size
}

/**
 * Logging decorator for debugging and analytics
 */
class LoggingDecorator(
    wrapped: NotificationProcessor,
    private val logger: NotificationLogger
) : NotificationProcessorDecorator(wrapped) {
    
    override fun process(notification: Notification, preferences: UserPreferences): ProcessingResult {
        logger.logProcessingStart(notification)
        
        val result = wrapped.process(notification, preferences)
        
        logger.logProcessingResult(notification, result)
        
        return result
    }
}

/** Logger interface for notification processing */
interface NotificationLogger {
    fun logProcessingStart(notification: Notification)
    fun logProcessingResult(notification: Notification, result: ProcessingResult)
}

/** Simple in-memory logger */
class InMemoryNotificationLogger : NotificationLogger {
    
    data class LogEntry(
        val notificationId: String,
        val event: String,
        val details: String,
        val timestamp: LocalDateTime = LocalDateTime.now()
    )
    
    private val logs = mutableListOf<LogEntry>()
    
    override fun logProcessingStart(notification: Notification) {
        logs.add(LogEntry(
            notificationId = notification.id,
            event = "PROCESSING_START",
            details = "Type: ${notification.type}, Priority: ${notification.priority}"
        ))
    }
    
    override fun logProcessingResult(notification: Notification, result: ProcessingResult) {
        val details = when (result) {
            is ProcessingResult.Continue -> "Continuing with processing"
            is ProcessingResult.Suppress -> "Suppressed: ${result.reason}"
            is ProcessingResult.Delay -> "Delayed until: ${result.until}"
            is ProcessingResult.Transform -> "Transformed"
        }
        
        logs.add(LogEntry(
            notificationId = notification.id,
            event = "PROCESSING_RESULT",
            details = details
        ))
    }
    
    fun getLogs(): List<LogEntry> = logs.toList()
    fun getLogsFor(notificationId: String): List<LogEntry> = 
        logs.filter { it.notificationId == notificationId }
    fun clear() = logs.clear()
}

/**
 * Conditional processor wrapper
 */
class ConditionalProcessor(
    wrapped: NotificationProcessor,
    private val condition: (Notification, UserPreferences) -> Boolean,
    private val processor: NotificationProcessor
) : NotificationProcessorDecorator(wrapped) {
    
    override fun process(notification: Notification, preferences: UserPreferences): ProcessingResult {
        return if (condition(notification, preferences)) {
            processor.process(notification, preferences)
        } else {
            wrapped.process(notification, preferences)
        }
    }
}

/**
 * Builder for constructing notification processing pipelines
 */
class ProcessingPipelineBuilder {
    
    private var processor: NotificationProcessor = BaseProcessor()
    
    fun withPriorityHandling(urgentBypass: Boolean = true): ProcessingPipelineBuilder {
        processor = PriorityDecorator(processor, urgentBypass)
        return this
    }
    
    fun withDND(timeProvider: () -> LocalDateTime = { LocalDateTime.now() }): ProcessingPipelineBuilder {
        processor = DNDDecorator(processor, timeProvider)
        return this
    }
    
    fun withDeduplication(
        windowMinutes: Int = 60,
        timeProvider: () -> LocalDateTime = { LocalDateTime.now() }
    ): ProcessingPipelineBuilder {
        processor = DeduplicationDecorator(processor, windowMinutes, timeProvider)
        return this
    }
    
    fun withRateLimit(
        config: RateLimitConfig = RateLimitConfig(),
        timeProvider: () -> LocalDateTime = { LocalDateTime.now() }
    ): ProcessingPipelineBuilder {
        processor = RateLimitDecorator(processor, config, timeProvider)
        return this
    }
    
    fun withCategoryFilter(): ProcessingPipelineBuilder {
        processor = CategoryFilterDecorator(processor)
        return this
    }
    
    fun withContentValidation(
        maxTitleLength: Int = 100,
        maxBodyLength: Int = 500
    ): ProcessingPipelineBuilder {
        processor = ContentValidationDecorator(processor, maxTitleLength, maxBodyLength)
        return this
    }
    
    fun withExpirationCheck(
        timeProvider: () -> LocalDateTime = { LocalDateTime.now() }
    ): ProcessingPipelineBuilder {
        processor = ExpirationDecorator(processor, timeProvider)
        return this
    }
    
    fun withGrouping(
        maxGroupSize: Int = 5,
        groupWindowMinutes: Int = 5,
        timeProvider: () -> LocalDateTime = { LocalDateTime.now() }
    ): ProcessingPipelineBuilder {
        processor = GroupingDecorator(processor, maxGroupSize, groupWindowMinutes, timeProvider)
        return this
    }
    
    fun withLogging(logger: NotificationLogger): ProcessingPipelineBuilder {
        processor = LoggingDecorator(processor, logger)
        return this
    }
    
    fun withConditional(
        condition: (Notification, UserPreferences) -> Boolean,
        processor: NotificationProcessor
    ): ProcessingPipelineBuilder {
        this.processor = ConditionalProcessor(this.processor, condition, processor)
        return this
    }
    
    fun build(): NotificationProcessor = processor
}

/**
 * Notification processing service
 */
class NotificationProcessingService(
    private val processor: NotificationProcessor
) {
    fun process(notification: Notification, preferences: UserPreferences): ProcessingResult {
        return processor.process(notification, preferences)
    }
    
    fun processBatch(
        notifications: List<Notification>,
        preferencesProvider: (String) -> UserPreferences?
    ): List<Pair<Notification, ProcessingResult>> {
        return notifications.mapNotNull { notification ->
            val preferences = preferencesProvider(notification.userId)
            if (preferences != null) {
                notification to processor.process(notification, preferences)
            } else {
                notification to ProcessingResult.Suppress("User preferences not found")
            }
        }
    }
}
