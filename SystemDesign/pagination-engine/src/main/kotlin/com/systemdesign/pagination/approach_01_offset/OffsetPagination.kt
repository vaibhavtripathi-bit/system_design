/**
 * # Approach 01: Offset-Based Pagination
 *
 * ## Pattern Used
 * Traditional OFFSET/LIMIT pagination using page numbers.
 *
 * ## Trade-offs
 * - **Pros:** Simple, random access to any page, familiar UX
 * - **Cons:** Expensive for large offsets (O(n)), inconsistent with concurrent writes
 *
 * ## When to Prefer
 * - Small to medium datasets
 * - When page numbers are needed in UI
 */
package com.systemdesign.pagination.approach_01_offset

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class Page<T>(
    val items: List<T>,
    val pageNumber: Int,
    val pageSize: Int,
    val totalItems: Long,
    val totalPages: Int
) {
    val hasNext: Boolean get() = pageNumber < totalPages
    val hasPrevious: Boolean get() = pageNumber > 1
}

data class OffsetRequest(
    val page: Int = 1,
    val pageSize: Int = 20
) {
    init {
        require(page >= 1) { "Page must be >= 1" }
        require(pageSize in 1..100) { "PageSize must be between 1 and 100" }
    }
    
    val offset: Int get() = (page - 1) * pageSize
}

interface OffsetDataSource<T> {
    suspend fun count(): Long
    suspend fun fetch(offset: Int, limit: Int): List<T>
}

class OffsetPaginator<T>(
    private val dataSource: OffsetDataSource<T>
) {
    suspend fun getPage(request: OffsetRequest): Page<T> {
        val totalItems = dataSource.count()
        val totalPages = ((totalItems + request.pageSize - 1) / request.pageSize).toInt()
        val items = dataSource.fetch(request.offset, request.pageSize)
        
        return Page(
            items = items,
            pageNumber = request.page,
            pageSize = request.pageSize,
            totalItems = totalItems,
            totalPages = totalPages.coerceAtLeast(1)
        )
    }

    fun getAllPages(pageSize: Int = 20): Flow<Page<T>> = flow {
        var page = 1
        do {
            val result = getPage(OffsetRequest(page, pageSize))
            emit(result)
            page++
        } while (result.hasNext)
    }
}
