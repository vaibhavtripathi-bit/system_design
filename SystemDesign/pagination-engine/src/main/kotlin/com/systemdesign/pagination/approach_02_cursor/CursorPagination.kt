/**
 * # Approach 02: Cursor-Based Pagination
 *
 * ## Pattern Used
 * Uses an opaque cursor (encoded position) for stable pagination.
 *
 * ## Trade-offs
 * - **Pros:** Consistent with concurrent writes, efficient (no offset scan)
 * - **Cons:** No random access, cursor management complexity
 *
 * ## When to Prefer
 * - Large datasets
 * - Real-time data with frequent updates
 * - Infinite scroll UIs
 */
package com.systemdesign.pagination.approach_02_cursor

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.Base64

data class CursorPage<T>(
    val items: List<T>,
    val startCursor: String?,
    val endCursor: String?,
    val hasNextPage: Boolean,
    val hasPreviousPage: Boolean
)

data class CursorRequest(
    val first: Int? = null,
    val after: String? = null,
    val last: Int? = null,
    val before: String? = null
) {
    init {
        require((first != null) xor (last != null)) { "Specify either first or last, not both" }
        require(first == null || first in 1..100) { "first must be between 1 and 100" }
        require(last == null || last in 1..100) { "last must be between 1 and 100" }
    }
    
    val limit: Int get() = first ?: last ?: 20
    val isForward: Boolean get() = first != null
}

interface CursorDataSource<T> {
    suspend fun fetchAfter(cursor: String?, limit: Int): List<T>
    suspend fun fetchBefore(cursor: String?, limit: Int): List<T>
    fun getCursor(item: T): String
}

object CursorEncoder {
    fun encode(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray())
    fun decode(cursor: String): String = String(Base64.getDecoder().decode(cursor))
}

class CursorPaginator<T>(
    private val dataSource: CursorDataSource<T>
) {
    suspend fun getPage(request: CursorRequest): CursorPage<T> {
        val items = if (request.isForward) {
            dataSource.fetchAfter(request.after, request.limit + 1)
        } else {
            dataSource.fetchBefore(request.before, request.limit + 1)
        }

        val hasMore = items.size > request.limit
        val trimmedItems = if (hasMore) items.dropLast(1) else items
        val orderedItems = if (request.isForward) trimmedItems else trimmedItems.reversed()

        return CursorPage(
            items = orderedItems,
            startCursor = orderedItems.firstOrNull()?.let { dataSource.getCursor(it) },
            endCursor = orderedItems.lastOrNull()?.let { dataSource.getCursor(it) },
            hasNextPage = if (request.isForward) hasMore else request.before != null,
            hasPreviousPage = if (request.isForward) request.after != null else hasMore
        )
    }

    fun getAllPages(pageSize: Int = 20): Flow<CursorPage<T>> = flow {
        var cursor: String? = null
        do {
            val result = getPage(CursorRequest(first = pageSize, after = cursor))
            emit(result)
            cursor = result.endCursor
        } while (result.hasNextPage)
    }
}
