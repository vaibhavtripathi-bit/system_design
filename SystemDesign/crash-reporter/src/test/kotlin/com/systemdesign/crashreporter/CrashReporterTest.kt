package com.systemdesign.crashreporter

import com.systemdesign.crashreporter.approach_01_simple.*
import com.systemdesign.crashreporter.approach_02_persistent.*
import com.systemdesign.crashreporter.approach_03_enhanced.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CrashReporterTest {

    // Simple Crash Reporter Tests
    @Test
    fun `simple - records exception`() {
        val reporter = SimpleCrashReporter()
        
        reporter.recordException(RuntimeException("Test error"))
        
        assertEquals(1, reporter.getReports().size)
        assertEquals("Test error", reporter.getReports()[0].message)
    }

    @Test
    fun `simple - includes metadata`() {
        val reporter = SimpleCrashReporter()
        reporter.setMetadata("version", "1.0.0")
        reporter.setMetadata("userId", "user123")
        
        reporter.recordException(RuntimeException("Test"))
        
        val report = reporter.getReports()[0]
        assertEquals("1.0.0", report.metadata["version"])
        assertEquals("user123", report.metadata["userId"])
    }

    @Test
    fun `simple - clears reports`() {
        val reporter = SimpleCrashReporter()
        reporter.recordException(RuntimeException("Test"))
        
        reporter.clearReports()
        
        assertTrue(reporter.getReports().isEmpty())
    }

    // Persistent Crash Reporter Tests
    @Test
    fun `persistent - saves to storage`() = runBlocking {
        val storage = InMemoryCrashStorage()
        val reporter = PersistentCrashReporter(storage)
        
        reporter.recordException(RuntimeException("Test"))
        
        assertEquals(1, storage.loadAll().size)
        reporter.shutdown()
    }

    @Test
    fun `persistent - sends and clears`() = runBlocking {
        val storage = InMemoryCrashStorage()
        val sent = mutableListOf<CrashReport>()
        val sender = object : CrashReportSender {
            override suspend fun send(report: CrashReport): Boolean {
                sent.add(report)
                return true
            }
        }
        val reporter = PersistentCrashReporter(storage, sender)
        
        reporter.recordException(RuntimeException("Test"))
        val count = reporter.sendPendingReports()
        
        assertEquals(1, count)
        assertEquals(1, sent.size)
        assertTrue(storage.loadAll().isEmpty())
        reporter.shutdown()
    }

    // Enhanced Crash Reporter Tests
    @Test
    fun `enhanced - records breadcrumbs`() {
        val reporter = EnhancedCrashReporter()
        
        reporter.addBreadcrumb("User clicked button", "ui")
        reporter.addBreadcrumb("API call started", "network")
        reporter.captureException(RuntimeException("Test"))
        
        val report = reporter.getReports()[0]
        assertEquals(2, report.breadcrumbs.size)
        reporter.shutdown()
    }

    @Test
    fun `enhanced - limits breadcrumbs`() {
        val reporter = EnhancedCrashReporter(maxBreadcrumbs = 5)
        
        repeat(10) { reporter.addBreadcrumb("Breadcrumb $it") }
        reporter.captureException(RuntimeException("Test"))
        
        val report = reporter.getReports()[0]
        assertEquals(5, report.breadcrumbs.size)
        assertEquals("Breadcrumb 5", report.breadcrumbs[0].message)
        reporter.shutdown()
    }

    @Test
    fun `enhanced - includes user info`() {
        val reporter = EnhancedCrashReporter()
        reporter.setUser(UserInfo(id = "123", email = "test@test.com", name = "Test User"))
        
        reporter.captureException(RuntimeException("Test"))
        
        val report = reporter.getReports()[0]
        assertEquals("123", report.userInfo?.id)
        assertEquals("test@test.com", report.userInfo?.email)
        reporter.shutdown()
    }

    @Test
    fun `enhanced - includes tags and extras`() {
        val reporter = EnhancedCrashReporter()
        reporter.setTag("environment", "production")
        reporter.setExtra("request_id", "abc123")
        
        reporter.captureException(RuntimeException("Test"))
        
        val report = reporter.getReports()[0]
        assertEquals("production", report.tags["environment"])
        assertEquals("abc123", report.extras["request_id"])
        reporter.shutdown()
    }

    @Test
    fun `enhanced - collects device info`() {
        val reporter = EnhancedCrashReporter()
        
        reporter.captureException(RuntimeException("Test"))
        
        val report = reporter.getReports()[0]
        assertTrue(report.deviceInfo.containsKey("os"))
        assertTrue(report.deviceInfo.containsKey("java.version"))
        reporter.shutdown()
    }

    @Test
    fun `enhanced - captures message`() {
        val reporter = EnhancedCrashReporter()
        
        val id = reporter.captureMessage("Something happened", BreadcrumbLevel.WARNING)
        
        assertNotNull(id)
        assertEquals(1, reporter.getReports().size)
        reporter.shutdown()
    }
}
