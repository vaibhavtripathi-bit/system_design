/**
 * # Approach 03: Enhanced Crash Reporter
 *
 * ## Pattern Used
 * Rich context collection with breadcrumbs and user info.
 *
 * ## Trade-offs
 * - **Pros:** Rich debugging info, breadcrumb trail, user context
 * - **Cons:** Memory overhead, privacy considerations
 */
package com.systemdesign.crashreporter.approach_03_enhanced

import com.systemdesign.crashreporter.approach_01_simple.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

data class Breadcrumb(
    val message: String,
    val category: String = "default",
    val level: BreadcrumbLevel = BreadcrumbLevel.INFO,
    val data: Map<String, Any> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)

enum class BreadcrumbLevel { DEBUG, INFO, WARNING, ERROR }

data class UserInfo(
    val id: String? = null,
    val email: String? = null,
    val name: String? = null,
    val extras: Map<String, String> = emptyMap()
)

data class EnhancedCrashReport(
    val id: String = java.util.UUID.randomUUID().toString(),
    val throwable: Throwable,
    val stackTrace: String,
    val timestamp: Long = System.currentTimeMillis(),
    val breadcrumbs: List<Breadcrumb>,
    val userInfo: UserInfo?,
    val tags: Map<String, String>,
    val extras: Map<String, Any>,
    val deviceInfo: Map<String, String>
)

class EnhancedCrashReporter(
    private val maxBreadcrumbs: Int = 100,
    private val sender: (suspend (EnhancedCrashReport) -> Boolean)? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val breadcrumbs = ConcurrentLinkedDeque<Breadcrumb>()
    private val tags = ConcurrentHashMap<String, String>()
    private val extras = ConcurrentHashMap<String, Any>()
    private var userInfo: UserInfo? = null
    private val reports = mutableListOf<EnhancedCrashReport>()
    private var originalHandler: Thread.UncaughtExceptionHandler? = null

    fun install() {
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            captureException(throwable)
            originalHandler?.uncaughtException(thread, throwable)
        }
    }

    fun uninstall() {
        Thread.setDefaultUncaughtExceptionHandler(originalHandler)
    }

    fun addBreadcrumb(breadcrumb: Breadcrumb) {
        breadcrumbs.addLast(breadcrumb)
        while (breadcrumbs.size > maxBreadcrumbs) {
            breadcrumbs.pollFirst()
        }
    }

    fun addBreadcrumb(message: String, category: String = "default") {
        addBreadcrumb(Breadcrumb(message, category))
    }

    fun setTag(key: String, value: String) {
        tags[key] = value
    }

    fun setExtra(key: String, value: Any) {
        extras[key] = value
    }

    fun setUser(info: UserInfo?) {
        userInfo = info
    }

    fun captureException(throwable: Throwable): String {
        val report = EnhancedCrashReport(
            throwable = throwable,
            stackTrace = throwable.stackTraceToString(),
            breadcrumbs = breadcrumbs.toList(),
            userInfo = userInfo,
            tags = tags.toMap(),
            extras = extras.toMap(),
            deviceInfo = collectDeviceInfo()
        )
        
        synchronized(reports) { reports.add(report) }
        
        scope.launch {
            sender?.invoke(report)
        }
        
        return report.id
    }

    fun captureMessage(message: String, level: BreadcrumbLevel = BreadcrumbLevel.INFO): String {
        addBreadcrumb(Breadcrumb(message, "message", level))
        return captureException(RuntimeException(message))
    }

    private fun collectDeviceInfo(): Map<String, String> {
        return mapOf(
            "os" to System.getProperty("os.name", "unknown"),
            "os.version" to System.getProperty("os.version", "unknown"),
            "java.version" to System.getProperty("java.version", "unknown"),
            "available.processors" to Runtime.getRuntime().availableProcessors().toString(),
            "max.memory" to Runtime.getRuntime().maxMemory().toString()
        )
    }

    fun getReports(): List<EnhancedCrashReport> = synchronized(reports) { reports.toList() }

    fun clearBreadcrumbs() = breadcrumbs.clear()

    fun clearReports() = synchronized(reports) { reports.clear() }

    fun shutdown() {
        scope.cancel()
    }
}
