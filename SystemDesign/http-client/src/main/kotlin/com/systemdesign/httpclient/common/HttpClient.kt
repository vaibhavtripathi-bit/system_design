/**
 * Common interfaces for HTTP Client implementations.
 */
package com.systemdesign.httpclient.common

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpRequest) return false
        return method == other.method && url == other.url && 
               headers == other.headers && body?.contentEquals(other.body) ?: (other.body == null)
    }
    override fun hashCode() = arrayOf(method, url, headers, body?.contentHashCode()).contentHashCode()
}

data class HttpResponse(
    val statusCode: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null
) {
    val isSuccessful: Boolean get() = statusCode in 200..299
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpResponse) return false
        return statusCode == other.statusCode && headers == other.headers &&
               body?.contentEquals(other.body) ?: (other.body == null)
    }
    override fun hashCode() = arrayOf(statusCode, headers, body?.contentHashCode()).contentHashCode()
}

sealed class HttpResult {
    data class Success(val response: HttpResponse) : HttpResult()
    data class Failure(val error: Throwable) : HttpResult()
}

interface HttpEngine {
    suspend fun execute(request: HttpRequest): HttpResponse
}

interface ResiliencePolicy {
    suspend fun <T> execute(block: suspend () -> T): T
}

interface Clock {
    fun now(): Long
}

object SystemClock : Clock {
    override fun now(): Long = System.currentTimeMillis()
}

class FakeClock(private var time: Long = 0) : Clock {
    override fun now(): Long = time
    fun advance(ms: Long) { time += ms }
    fun set(ms: Long) { time = ms }
}
