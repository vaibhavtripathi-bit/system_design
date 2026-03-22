/**
 * # Approach 02: Persistent Crash Reporter
 *
 * ## Pattern Used
 * File-based storage with batched sending.
 *
 * ## Trade-offs
 * - **Pros:** Survives restarts, batched sending
 * - **Cons:** I/O overhead, file management
 */
package com.systemdesign.crashreporter.approach_02_persistent

import com.systemdesign.crashreporter.approach_01_simple.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

interface CrashStorage {
    suspend fun save(report: CrashReport)
    suspend fun loadAll(): List<CrashReport>
    suspend fun delete(id: String)
    suspend fun clear()
}

class InMemoryCrashStorage : CrashStorage {
    private val reports = ConcurrentHashMap<String, CrashReport>()
    
    override suspend fun save(report: CrashReport) { reports[report.id] = report }
    override suspend fun loadAll() = reports.values.toList()
    override suspend fun delete(id: String) { reports.remove(id) }
    override suspend fun clear() = reports.clear()
}

class PersistentCrashReporter(
    private val storage: CrashStorage,
    private val sender: CrashReportSender? = null,
    private val batchSize: Int = 10,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val metadata = ConcurrentHashMap<String, String>()
    private var originalHandler: Thread.UncaughtExceptionHandler? = null

    fun install() {
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runBlocking { recordException(throwable) }
            originalHandler?.uncaughtException(thread, throwable)
        }
    }

    fun uninstall() {
        Thread.setDefaultUncaughtExceptionHandler(originalHandler)
    }

    fun setMetadata(key: String, value: String) {
        metadata[key] = value
    }

    suspend fun recordException(throwable: Throwable) {
        val report = CrashReport(
            throwable = throwable,
            metadata = metadata.toMap()
        )
        storage.save(report)
    }

    suspend fun sendPendingReports(): Int {
        sender ?: return 0
        val reports = storage.loadAll()
        var sent = 0
        
        reports.chunked(batchSize).forEach { batch ->
            batch.forEach { report ->
                if (sender.send(report)) {
                    storage.delete(report.id)
                    sent++
                }
            }
        }
        
        return sent
    }

    suspend fun getReports(): List<CrashReport> = storage.loadAll()

    suspend fun clearReports() = storage.clear()

    fun shutdown() {
        scope.cancel()
    }
}
