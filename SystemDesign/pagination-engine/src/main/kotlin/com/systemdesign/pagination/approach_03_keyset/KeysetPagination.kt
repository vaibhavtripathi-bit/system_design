/**
 * # Approach 03: Keyset (Seek) Pagination
 *
 * ## Pattern Used
 * Uses indexed column values for efficient seeking.
 *
 * ## Trade-offs
 * - **Pros:** Most efficient, O(log n) seeks, consistent results
 * - **Cons:** Requires unique sortable column, no random access
 *
 * ## When to Prefer
 * - Very large datasets
 * - When performance is critical
 * - Time-series data
 */
package com.systemdesign.pagination.approach_03_keyset

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class KeysetPage<T, K : Comparable<K>>(
    val items: List<T>,
    val nextKey: K?,
    val previousKey: K?,
    val hasNext: Boolean,
    val hasPrevious: Boolean
)

data class KeysetRequest<K : Comparable<K>>(
    val limit: Int = 20,
    val afterKey: K? = null,
    val beforeKey: K? = null,
    val direction: Direction = Direction.FORWARD
) {
    init {
        require(limit in 1..100) { "Limit must be between 1 and 100" }
        require(afterKey == null || beforeKey == null) { "Cannot specify both afterKey and beforeKey" }
    }
    
    enum class Direction { FORWARD, BACKWARD }
}

interface KeysetDataSource<T, K : Comparable<K>> {
    suspend fun fetchAfter(key: K?, limit: Int): List<T>
    suspend fun fetchBefore(key: K?, limit: Int): List<T>
    fun getKey(item: T): K
}

class KeysetPaginator<T, K : Comparable<K>>(
    private val dataSource: KeysetDataSource<T, K>
) {
    suspend fun getPage(request: KeysetRequest<K>): KeysetPage<T, K> {
        val items = when (request.direction) {
            KeysetRequest.Direction.FORWARD -> {
                dataSource.fetchAfter(request.afterKey, request.limit + 1)
            }
            KeysetRequest.Direction.BACKWARD -> {
                dataSource.fetchBefore(request.beforeKey, request.limit + 1)
            }
        }

        val hasMore = items.size > request.limit
        val trimmedItems = if (hasMore) items.dropLast(1) else items
        val orderedItems = when (request.direction) {
            KeysetRequest.Direction.FORWARD -> trimmedItems
            KeysetRequest.Direction.BACKWARD -> trimmedItems.reversed()
        }

        return KeysetPage(
            items = orderedItems,
            nextKey = orderedItems.lastOrNull()?.let { dataSource.getKey(it) },
            previousKey = orderedItems.firstOrNull()?.let { dataSource.getKey(it) },
            hasNext = when (request.direction) {
                KeysetRequest.Direction.FORWARD -> hasMore
                KeysetRequest.Direction.BACKWARD -> request.beforeKey != null
            },
            hasPrevious = when (request.direction) {
                KeysetRequest.Direction.FORWARD -> request.afterKey != null
                KeysetRequest.Direction.BACKWARD -> hasMore
            }
        )
    }

    fun getAllPages(pageSize: Int = 20): Flow<KeysetPage<T, K>> = flow {
        var lastKey: K? = null
        do {
            val request = KeysetRequest(limit = pageSize, afterKey = lastKey)
            val result = getPage(request)
            emit(result)
            lastKey = result.nextKey
        } while (result.hasNext)
    }
}
