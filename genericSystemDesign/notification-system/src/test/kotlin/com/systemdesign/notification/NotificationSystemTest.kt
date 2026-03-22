package com.systemdesign.notification

import com.systemdesign.notification.common.*
import com.systemdesign.notification.approach_01_chain_responsibility.*
import com.systemdesign.notification.approach_02_decorator_processing.*
import com.systemdesign.notification.approach_03_strategy_delivery.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.DayOfWeek

class NotificationSystemTest {
    
    private fun createTestNotification(
        id: String = "notif-1",
        userId: String = "user-1",
        type: NotificationType = NotificationType.PUSH,
        priority: NotificationPriority = NotificationPriority.NORMAL,
        category: NotificationCategory = NotificationCategory.SYSTEM
    ): Notification {
        return Notification(
            id = id,
            userId = userId,
            title = "Test Notification",
            body = "This is a test notification body",
            type = type,
            priority = priority,
            category = category
        )
    }
    
    private fun createTestPreferences(
        userId: String = "user-1",
        enabledChannels: Set<NotificationType> = NotificationType.entries.toSet(),
        emailAddress: String? = "user@example.com",
        phoneNumber: String? = "+1234567890",
        pushTokens: List<PushToken> = listOf(
            PushToken("token-1", PushToken.Platform.ANDROID, "device-1")
        )
    ): UserPreferences {
        return UserPreferences(
            userId = userId,
            enabledChannels = enabledChannels,
            emailAddress = emailAddress,
            phoneNumber = phoneNumber,
            pushTokens = pushTokens
        )
    }
    
    @Nested
    inner class ChainOfResponsibilityTest {
        
        private lateinit var pushService: MockPushService
        private lateinit var emailService: MockEmailService
        private lateinit var smsService: MockSmsService
        private lateinit var inAppService: MockInAppNotificationService
        private lateinit var preferencesRepository: InMemoryUserPreferencesRepository
        
        @BeforeEach
        fun setup() {
            pushService = MockPushService()
            emailService = MockEmailService()
            smsService = MockSmsService()
            inAppService = MockInAppNotificationService()
            preferencesRepository = InMemoryUserPreferencesRepository()
        }
        
        @Test
        fun `chain delivers via first available channel`() {
            preferencesRepository.savePreferences(createTestPreferences())
            
            val chain = NotificationChainBuilder()
                .addPush(pushService)
                .addEmail(emailService)
                .addInApp(inAppService)
                .build()!!
            
            val service = ChainNotificationService(chain, preferencesRepository)
            val notification = createTestNotification()
            
            val result = service.send(notification)
            
            assertTrue(result.isSuccess())
            assertEquals(NotificationType.PUSH, result.channel)
            assertEquals(1, pushService.sentNotifications.size)
            assertEquals(0, emailService.sentEmails.size)
        }
        
        @Test
        fun `chain falls back to next channel on failure`() {
            pushService.setShouldSucceed(false)
            preferencesRepository.savePreferences(createTestPreferences())
            
            val chain = NotificationChainBuilder()
                .addPush(pushService)
                .addEmail(emailService)
                .addInApp(inAppService)
                .build()!!
            
            val service = ChainNotificationService(chain, preferencesRepository)
            val notification = createTestNotification()
            
            val result = service.send(notification)
            
            assertTrue(result.isSuccess())
            assertEquals(NotificationType.EMAIL, result.channel)
            assertEquals(1, emailService.sentEmails.size)
        }
        
        @Test
        fun `chain skips unavailable channels`() {
            pushService.setAvailable(false)
            preferencesRepository.savePreferences(createTestPreferences())
            
            val chain = NotificationChainBuilder()
                .addPush(pushService)
                .addEmail(emailService)
                .build()!!
            
            val service = ChainNotificationService(chain, preferencesRepository)
            val notification = createTestNotification()
            
            val result = service.send(notification)
            
            assertTrue(result.isSuccess())
            assertEquals(NotificationType.EMAIL, result.channel)
        }
        
        @Test
        fun `chain respects user channel preferences`() {
            val preferences = createTestPreferences(
                enabledChannels = setOf(NotificationType.EMAIL, NotificationType.IN_APP)
            )
            preferencesRepository.savePreferences(preferences)
            
            val chain = NotificationChainBuilder()
                .addPush(pushService)
                .addEmail(emailService)
                .addInApp(inAppService)
                .build()!!
            
            val service = ChainNotificationService(chain, preferencesRepository)
            val notification = createTestNotification()
            
            val result = service.send(notification)
            
            assertTrue(result.isSuccess())
            assertEquals(NotificationType.EMAIL, result.channel)
            assertEquals(0, pushService.sentNotifications.size)
        }
        
        @Test
        fun `chain fails if no channel can deliver`() {
            pushService.setShouldSucceed(false)
            emailService.setShouldSucceed(false)
            smsService.setShouldSucceed(false)
            
            val preferences = createTestPreferences(
                enabledChannels = setOf(NotificationType.PUSH, NotificationType.EMAIL, NotificationType.SMS)
            )
            preferencesRepository.savePreferences(preferences)
            
            val chain = NotificationChainBuilder()
                .addPush(pushService)
                .addEmail(emailService)
                .addSms(smsService)
                .build()!!
            
            val service = ChainNotificationService(chain, preferencesRepository)
            val notification = createTestNotification()
            
            val result = service.send(notification)
            
            assertTrue(result.isFailed())
        }
        
        @Test
        fun `in-app always delivers as last resort`() {
            pushService.setShouldSucceed(false)
            emailService.setShouldSucceed(false)
            preferencesRepository.savePreferences(createTestPreferences())
            
            val chain = NotificationChainBuilder()
                .addPush(pushService)
                .addEmail(emailService)
                .addInApp(inAppService)
                .build()!!
            
            val service = ChainNotificationService(chain, preferencesRepository)
            val notification = createTestNotification()
            
            val result = service.send(notification)
            
            assertTrue(result.isSuccess())
            assertEquals(NotificationType.IN_APP, result.channel)
            assertEquals(1, inAppService.getAllStored().size)
        }
        
        @Test
        fun `observer receives events`() {
            preferencesRepository.savePreferences(createTestPreferences())
            
            val chain = NotificationChainBuilder()
                .addPush(pushService)
                .build()!!
            
            val service = ChainNotificationService(chain, preferencesRepository)
            
            var createdNotification: Notification? = null
            var sentResult: DeliveryResult? = null
            
            service.addObserver(object : NotificationObserver {
                override fun onNotificationCreated(notification: Notification) {
                    createdNotification = notification
                }
                override fun onNotificationSent(notification: Notification, result: DeliveryResult) {
                    sentResult = result
                }
                override fun onNotificationFailed(notification: Notification, result: DeliveryResult) {}
            })
            
            val notification = createTestNotification()
            service.send(notification)
            
            assertNotNull(createdNotification)
            assertNotNull(sentResult)
            assertTrue(sentResult!!.isSuccess())
        }
    }
    
    @Nested
    inner class DecoratorProcessingTest {
        
        @Test
        fun `base processor continues unchanged`() {
            val processor = BaseProcessor()
            val notification = createTestNotification()
            val preferences = createTestPreferences()
            
            val result = processor.process(notification, preferences)
            
            assertTrue(result is ProcessingResult.Continue)
            assertEquals(notification, (result as ProcessingResult.Continue).notification)
        }
        
        @Test
        fun `priority decorator bypasses processing for urgent`() {
            val processor = PriorityDecorator(
                DeduplicationDecorator(BaseProcessor(), windowMinutes = 60),
                urgentBypassProcessing = true
            )
            
            val notification = createTestNotification(priority = NotificationPriority.URGENT)
            val preferences = createTestPreferences()
            
            val result1 = processor.process(notification, preferences)
            val result2 = processor.process(notification, preferences)
            
            assertTrue(result1 is ProcessingResult.Continue)
            assertTrue(result2 is ProcessingResult.Continue)
        }
        
        @Test
        fun `DND decorator delays notifications during quiet hours`() {
            val quietHours = QuietHours(
                enabled = true,
                startTime = LocalTime.of(22, 0),
                endTime = LocalTime.of(8, 0),
                allowUrgent = true
            )
            val preferences = createTestPreferences().copy(quietHours = quietHours)
            
            val nightTime = LocalDateTime.now()
                .withHour(23)
                .withMinute(0)
            
            val processor = DNDDecorator(BaseProcessor()) { nightTime }
            val notification = createTestNotification()
            
            val result = processor.process(notification, preferences)
            
            assertTrue(result is ProcessingResult.Delay)
        }
        
        @Test
        fun `DND decorator allows urgent during quiet hours`() {
            val quietHours = QuietHours(
                enabled = true,
                startTime = LocalTime.of(22, 0),
                endTime = LocalTime.of(8, 0),
                allowUrgent = true
            )
            val preferences = createTestPreferences().copy(quietHours = quietHours)
            
            val nightTime = LocalDateTime.now()
                .withHour(23)
                .withMinute(0)
            
            val processor = DNDDecorator(BaseProcessor()) { nightTime }
            val notification = createTestNotification(priority = NotificationPriority.URGENT)
            
            val result = processor.process(notification, preferences)
            
            assertTrue(result is ProcessingResult.Continue)
        }
        
        @Test
        fun `deduplication suppresses duplicates`() {
            val now = LocalDateTime.now()
            val processor = DeduplicationDecorator(BaseProcessor(), windowMinutes = 60) { now }
            val notification = createTestNotification()
            val preferences = createTestPreferences()
            
            val result1 = processor.process(notification, preferences)
            val result2 = processor.process(notification, preferences)
            
            assertTrue(result1 is ProcessingResult.Continue)
            assertTrue(result2 is ProcessingResult.Suppress)
        }
        
        @Test
        fun `deduplication allows after window expires`() {
            var currentTime = LocalDateTime.now()
            val processor = DeduplicationDecorator(
                BaseProcessor(), 
                windowMinutes = 60
            ) { currentTime }
            
            val notification = createTestNotification()
            val preferences = createTestPreferences()
            
            val result1 = processor.process(notification, preferences)
            assertTrue(result1 is ProcessingResult.Continue)
            
            currentTime = currentTime.plusMinutes(61)
            
            val result2 = processor.process(notification, preferences)
            assertTrue(result2 is ProcessingResult.Continue)
        }
        
        @Test
        fun `rate limit suppresses excessive notifications`() {
            val config = RateLimitConfig(maxPerMinute = 2)
            val processor = RateLimitDecorator(BaseProcessor(), config)
            val preferences = createTestPreferences()
            
            val results = (1..5).map { i ->
                processor.process(
                    createTestNotification(id = "notif-$i"),
                    preferences
                )
            }
            
            assertEquals(2, results.count { it is ProcessingResult.Continue })
            assertEquals(3, results.count { it is ProcessingResult.Suppress })
        }
        
        @Test
        fun `rate limit allows urgent notifications`() {
            val config = RateLimitConfig(maxPerMinute = 1)
            val processor = RateLimitDecorator(BaseProcessor(), config)
            val preferences = createTestPreferences()
            
            processor.process(createTestNotification(id = "1"), preferences)
            
            val urgentNotification = createTestNotification(
                id = "2",
                priority = NotificationPriority.URGENT
            )
            val result = processor.process(urgentNotification, preferences)
            
            assertTrue(result is ProcessingResult.Continue)
        }
        
        @Test
        fun `content validation truncates long content`() {
            val processor = ContentValidationDecorator(BaseProcessor(), maxTitleLength = 10)
            val notification = createTestNotification().copy(title = "This is a very long title")
            val preferences = createTestPreferences()
            
            val result = processor.process(notification, preferences)
            
            assertTrue(result is ProcessingResult.Continue)
            assertEquals(10, (result as ProcessingResult.Continue).notification.title.length)
        }
        
        @Test
        fun `content validation suppresses empty content`() {
            val processor = ContentValidationDecorator(BaseProcessor())
            val notification = createTestNotification().copy(title = "")
            val preferences = createTestPreferences()
            
            val result = processor.process(notification, preferences)
            
            assertTrue(result is ProcessingResult.Suppress)
        }
        
        @Test
        fun `expiration decorator suppresses expired notifications`() {
            val now = LocalDateTime.now()
            val processor = ExpirationDecorator(BaseProcessor()) { now }
            
            val expiredNotification = createTestNotification().copy(
                expiresAt = now.minusHours(1)
            )
            val preferences = createTestPreferences()
            
            val result = processor.process(expiredNotification, preferences)
            
            assertTrue(result is ProcessingResult.Suppress)
        }
        
        @Test
        fun `pipeline builder creates stacked decorators`() {
            val config = RateLimitConfig(maxPerMinute = 5)
            
            val processor = ProcessingPipelineBuilder()
                .withPriorityHandling()
                .withDeduplication(windowMinutes = 30)
                .withRateLimit(config)
                .withContentValidation()
                .build()
            
            val notification = createTestNotification()
            val preferences = createTestPreferences()
            
            val result = processor.process(notification, preferences)
            
            assertTrue(result is ProcessingResult.Continue)
        }
        
        @Test
        fun `logging decorator logs processing`() {
            val logger = InMemoryNotificationLogger()
            val processor = LoggingDecorator(BaseProcessor(), logger)
            
            val notification = createTestNotification()
            val preferences = createTestPreferences()
            
            processor.process(notification, preferences)
            
            val logs = logger.getLogsFor(notification.id)
            assertEquals(2, logs.size)
            assertEquals("PROCESSING_START", logs[0].event)
            assertEquals("PROCESSING_RESULT", logs[1].event)
        }
    }
    
    @Nested
    inner class StrategyDeliveryTest {
        
        @Test
        fun `push strategy delivers to valid token`() {
            val provider = MockPushNotificationProvider()
            val strategy = PushNotificationStrategy(provider)
            
            val notification = createTestNotification(type = NotificationType.PUSH)
            val preferences = createTestPreferences()
            
            val result = strategy.deliver(notification, preferences)
            
            assertTrue(result.isSuccess())
            assertEquals(NotificationType.PUSH, result.channel)
            assertEquals(1, provider.deliveredPushes.size)
        }
        
        @Test
        fun `push strategy fails without valid token`() {
            val provider = MockPushNotificationProvider()
            val strategy = PushNotificationStrategy(provider)
            
            val notification = createTestNotification(type = NotificationType.PUSH)
            val preferences = createTestPreferences(pushTokens = emptyList())
            
            val result = strategy.deliver(notification, preferences)
            
            assertTrue(result.isFailed())
            assertEquals(FailureReason.INVALID_RECIPIENT, result.failureReason)
        }
        
        @Test
        fun `email strategy delivers with valid address`() {
            val provider = MockEmailProvider()
            val strategy = EmailStrategy(provider)
            
            val notification = createTestNotification(type = NotificationType.EMAIL)
            val preferences = createTestPreferences(emailAddress = "test@example.com")
            
            val result = strategy.deliver(notification, preferences)
            
            assertTrue(result.isSuccess())
            assertEquals(NotificationType.EMAIL, result.channel)
            assertEquals(1, provider.sentEmails.size)
            assertTrue(provider.sentEmails.first().htmlBody.contains(notification.title))
        }
        
        @Test
        fun `email strategy fails without address`() {
            val provider = MockEmailProvider()
            val strategy = EmailStrategy(provider)
            
            val notification = createTestNotification(type = NotificationType.EMAIL)
            val preferences = createTestPreferences(emailAddress = null)
            
            val result = strategy.deliver(notification, preferences)
            
            assertTrue(result.isFailed())
            assertEquals(FailureReason.INVALID_RECIPIENT, result.failureReason)
        }
        
        @Test
        fun `SMS strategy truncates long messages`() {
            val provider = MockSmsProvider()
            val strategy = SMSStrategy(provider, maxMessageLength = 50)
            
            val notification = createTestNotification(type = NotificationType.SMS)
            val preferences = createTestPreferences()
            
            val result = strategy.deliver(notification, preferences)
            
            assertTrue(result.isSuccess())
            assertEquals(50, provider.sentMessages.first().message.length)
            assertTrue(provider.sentMessages.first().message.endsWith("..."))
        }
        
        @Test
        fun `in-app strategy always succeeds`() {
            val store = InMemoryInAppNotificationStore()
            val strategy = InAppStrategy(store)
            
            val notification = createTestNotification(type = NotificationType.IN_APP)
            val preferences = createTestPreferences()
            
            val result = strategy.deliver(notification, preferences)
            
            assertTrue(result.isSuccess())
            assertEquals(1, store.getAll().size)
        }
        
        @Test
        fun `strategy selector finds correct strategy`() {
            val pushProvider = MockPushNotificationProvider()
            val emailProvider = MockEmailProvider()
            
            val selector = DeliveryStrategySelector(mapOf(
                NotificationType.PUSH to PushNotificationStrategy(pushProvider),
                NotificationType.EMAIL to EmailStrategy(emailProvider)
            ))
            
            val pushNotification = createTestNotification(type = NotificationType.PUSH)
            val emailNotification = createTestNotification(type = NotificationType.EMAIL)
            
            val pushStrategy = selector.selectStrategy(pushNotification)
            val emailStrategy = selector.selectStrategy(emailNotification)
            
            assertEquals(NotificationType.PUSH, pushStrategy?.channelType)
            assertEquals(NotificationType.EMAIL, emailStrategy?.channelType)
        }
        
        @Test
        fun `failover strategy tries backup channels`() {
            val pushProvider = MockPushNotificationProvider(shouldSucceed = false)
            val emailProvider = MockEmailProvider()
            
            val failoverStrategy = FailoverDeliveryStrategy(
                primaryStrategy = PushNotificationStrategy(pushProvider),
                fallbackStrategies = listOf(EmailStrategy(emailProvider))
            )
            
            val notification = createTestNotification(type = NotificationType.PUSH)
            val preferences = createTestPreferences()
            
            val result = failoverStrategy.deliver(notification, preferences)
            
            assertTrue(result.isSuccess())
            assertEquals(NotificationType.EMAIL, result.channel)
        }
        
        @Test
        fun `delivery service uses strategies correctly`() {
            val pushProvider = MockPushNotificationProvider()
            val preferencesRepository = InMemoryUserPreferencesRepository()
            preferencesRepository.savePreferences(createTestPreferences())
            
            val selector = DeliveryStrategySelector(mapOf(
                NotificationType.PUSH to PushNotificationStrategy(pushProvider)
            ))
            
            val service = StrategyDeliveryService(selector, preferencesRepository)
            val notification = createTestNotification(type = NotificationType.PUSH)
            
            val result = service.deliver(notification)
            
            assertTrue(result.isSuccess())
            assertEquals(1, pushProvider.deliveredPushes.size)
        }
    }
    
    @Nested
    inner class ModelTest {
        
        @Test
        fun `notification priority checks work`() {
            val normalNotification = createTestNotification(priority = NotificationPriority.NORMAL)
            val urgentNotification = createTestNotification(priority = NotificationPriority.URGENT)
            val highNotification = createTestNotification(priority = NotificationPriority.HIGH)
            
            assertFalse(normalNotification.isUrgent())
            assertTrue(urgentNotification.isUrgent())
            assertFalse(normalNotification.isHighPriority())
            assertTrue(highNotification.isHighPriority())
            assertTrue(urgentNotification.isHighPriority())
        }
        
        @Test
        fun `notification expiration works`() {
            val validNotification = createTestNotification().copy(
                expiresAt = LocalDateTime.now().plusHours(1)
            )
            val expiredNotification = createTestNotification().copy(
                expiresAt = LocalDateTime.now().minusHours(1)
            )
            
            assertFalse(validNotification.isExpired())
            assertTrue(expiredNotification.isExpired())
        }
        
        @Test
        fun `user preferences channel checks work`() {
            val fullPreferences = createTestPreferences()
            val limitedPreferences = createTestPreferences(
                enabledChannels = setOf(NotificationType.EMAIL)
            )
            
            assertTrue(fullPreferences.isChannelEnabled(NotificationType.PUSH))
            assertFalse(limitedPreferences.isChannelEnabled(NotificationType.PUSH))
            assertTrue(limitedPreferences.isChannelEnabled(NotificationType.EMAIL))
        }
        
        @Test
        fun `quiet hours active check works`() {
            val quietHours = QuietHours(
                enabled = true,
                startTime = LocalTime.of(22, 0),
                endTime = LocalTime.of(8, 0)
            )
            
            assertTrue(quietHours.isActive(LocalTime.of(23, 0), DayOfWeek.MONDAY))
            assertTrue(quietHours.isActive(LocalTime.of(2, 0), DayOfWeek.MONDAY))
            assertFalse(quietHours.isActive(LocalTime.of(12, 0), DayOfWeek.MONDAY))
        }
        
        @Test
        fun `push token validity check works`() {
            val validToken = PushToken(
                token = "valid",
                platform = PushToken.Platform.ANDROID,
                deviceId = "device-1",
                lastUsedAt = LocalDateTime.now()
            )
            val oldToken = PushToken(
                token = "old",
                platform = PushToken.Platform.IOS,
                deviceId = "device-2",
                lastUsedAt = LocalDateTime.now().minusDays(100)
            )
            
            assertTrue(validToken.isValid())
            assertFalse(oldToken.isValid())
        }
        
        @Test
        fun `delivery result status checks work`() {
            val successResult = DeliveryResult(
                notificationId = "1",
                channel = NotificationType.PUSH,
                status = DeliveryStatus.DELIVERED,
                deliveredAt = LocalDateTime.now()
            )
            val failedResult = DeliveryResult(
                notificationId = "2",
                channel = NotificationType.PUSH,
                status = DeliveryStatus.FAILED,
                failureReason = FailureReason.PROVIDER_ERROR
            )
            
            assertTrue(successResult.isSuccess())
            assertFalse(successResult.isFailed())
            assertFalse(failedResult.isSuccess())
            assertTrue(failedResult.isFailed())
        }
        
        @Test
        fun `notification template rendering works`() {
            val template = NotificationTemplate(
                id = "welcome",
                name = "Welcome Email",
                titleTemplate = "Welcome, {{name}}!",
                bodyTemplate = "Thanks for joining {{app}}.",
                category = NotificationCategory.TRANSACTIONAL
            )
            
            val (title, body) = template.render(mapOf(
                "name" to "John",
                "app" to "MyApp"
            ))
            
            assertEquals("Welcome, John!", title)
            assertEquals("Thanks for joining MyApp.", body)
        }
    }
}
