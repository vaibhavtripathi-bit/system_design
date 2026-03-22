/**
 * # Approach 01: Simple Crash Reporter
 *
 * ## Pattern Used
 * Basic exception handling with in-memory storage.
 *
 * ## Trade-offs
 * - **Pros:** Simple, low overhead
 * - **Cons:** Lost on restart, no batching
 */
package com.systemdesign.crashreporter.approach_01_simple

import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

data class CrashReport(
    val id: String = java.util.UUID.randomUUID().toString(),
    val throwable: Throwable,
    val message: String = throwable.message ?: "",
    val stackTrace: String = throwable.stackTraceToString(),
    val timestamp: Long = System.currentTimeMillis(),
    val metadata: Map<String, String> = emptyMap()
)

interface CrashReportSender {
    suspend fun send(report: CrashReport): Boolean
}

class SimpleCrashReporter(
    private val sender: CrashReportSender? = null
) {
    private val reports = CopyOnWriteArrayList<CrashReport>()
    private val metadata = ConcurrentHashMap<String, String>()
    private var originalHandler: Thread.UncaughtExceptionHandler? = null

    fun install() {
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            recordException(throwable)
            originalHandler?.uncaughtException(thread, throwable)
        }
    }

    fun uninstall() {
        Thread.setDefaultUncaughtExceptionHandler(originalHandler)
    }

    fun setMetadata(key: String, value: String) {
        metadata[key] = value
    }

    fun recordException(throwable: Throwable) {
        val report = CrashReport(
            throwable = throwable,
            metadata = metadata.toMap()
        )
        reports.add(report)
    }

    suspend fun sendPendingReports() {
        sender ?: return
        val toSend = reports.toList()
        toSend.forEach { report ->
            if (sender.send(report)) {
                reports.remove(report)
            }
        }
    }

    fun getReports(): List<CrashReport> = reports.toList()

    fun clearReports() = reports.clear()
}

fun Throwable.stackTraceToString(): String {
    val sw = StringWriter()
    printStackTrace(PrintWriter(sw))
    return sw.toString()
}
