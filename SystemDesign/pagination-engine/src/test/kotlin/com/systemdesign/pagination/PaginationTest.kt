package com.systemdesign.pagination

import com.systemdesign.pagination.approach_01_offset.*
import com.systemdesign.pagination.approach_02_cursor.*
import com.systemdesign.pagination.approach_03_keyset.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

data class Item(val id: Long, val name: String)

class PaginationTest {

    private val testData = (1L..50L).map { Item(it, "Item $it") }

    // Offset Pagination Tests
    class TestOffsetDataSource(private val data: List<Item>) : OffsetDataSource<Item> {
        override suspend fun count(): Long = data.size.toLong()
        override suspend fun fetch(offset: Int, limit: Int): List<Item> =
            data.drop(offset).take(limit)
    }

    @Test
    fun `offset - returns first page`() = runBlocking {
        val dataSource = TestOffsetDataSource(testData)
        val paginator = OffsetPaginator(dataSource)
        
        val page = paginator.getPage(OffsetRequest(page = 1, pageSize = 10))
        
        assertEquals(10, page.items.size)
        assertEquals(1L, page.items.first().id)
        assertEquals(1, page.pageNumber)
        assertEquals(50L, page.totalItems)
        assertEquals(5, page.totalPages)
        assertTrue(page.hasNext)
        assertFalse(page.hasPrevious)
    }

    @Test
    fun `offset - returns middle page`() = runBlocking {
        val dataSource = TestOffsetDataSource(testData)
        val paginator = OffsetPaginator(dataSource)
        
        val page = paginator.getPage(OffsetRequest(page = 3, pageSize = 10))
        
        assertEquals(21L, page.items.first().id)
        assertTrue(page.hasNext)
        assertTrue(page.hasPrevious)
    }

    @Test
    fun `offset - returns last page`() = runBlocking {
        val dataSource = TestOffsetDataSource(testData)
        val paginator = OffsetPaginator(dataSource)
        
        val page = paginator.getPage(OffsetRequest(page = 5, pageSize = 10))
        
        assertEquals(10, page.items.size)
        assertEquals(41L, page.items.first().id)
        assertFalse(page.hasNext)
        assertTrue(page.hasPrevious)
    }

    @Test
    fun `offset - iterates all pages`() = runBlocking {
        val dataSource = TestOffsetDataSource(testData)
        val paginator = OffsetPaginator(dataSource)
        
        val pages = paginator.getAllPages(pageSize = 10).toList()
        
        assertEquals(5, pages.size)
        assertEquals(50, pages.flatMap { it.items }.size)
    }

    // Cursor Pagination Tests
    class TestCursorDataSource(private val data: List<Item>) : CursorDataSource<Item> {
        override suspend fun fetchAfter(cursor: String?, limit: Int): List<Item> {
            val startIdx = if (cursor == null) 0 else {
                val id = CursorEncoder.decode(cursor).toLong()
                data.indexOfFirst { it.id > id }.takeIf { it >= 0 } ?: data.size
            }
            return data.drop(startIdx).take(limit)
        }

        override suspend fun fetchBefore(cursor: String?, limit: Int): List<Item> {
            val endIdx = if (cursor == null) data.size else {
                val id = CursorEncoder.decode(cursor).toLong()
                data.indexOfFirst { it.id >= id }.takeIf { it >= 0 } ?: data.size
            }
            return data.take(endIdx).takeLast(limit)
        }

        override fun getCursor(item: Item): String = CursorEncoder.encode(item.id.toString())
    }

    @Test
    fun `cursor - returns first page`() = runBlocking {
        val dataSource = TestCursorDataSource(testData)
        val paginator = CursorPaginator(dataSource)
        
        val page = paginator.getPage(CursorRequest(first = 10))
        
        assertEquals(10, page.items.size)
        assertEquals(1L, page.items.first().id)
        assertTrue(page.hasNextPage)
        assertFalse(page.hasPreviousPage)
    }

    @Test
    fun `cursor - fetches after cursor`() = runBlocking {
        val dataSource = TestCursorDataSource(testData)
        val paginator = CursorPaginator(dataSource)
        
        val firstPage = paginator.getPage(CursorRequest(first = 10))
        val secondPage = paginator.getPage(CursorRequest(first = 10, after = firstPage.endCursor))
        
        assertEquals(11L, secondPage.items.first().id)
        assertTrue(secondPage.hasNextPage)
        assertTrue(secondPage.hasPreviousPage)
    }

    @Test
    fun `cursor - iterates all pages`() = runBlocking {
        val dataSource = TestCursorDataSource(testData)
        val paginator = CursorPaginator(dataSource)
        
        val pages = paginator.getAllPages(pageSize = 10).toList()
        
        assertEquals(5, pages.size)
        assertEquals(50, pages.flatMap { it.items }.size)
    }

    // Keyset Pagination Tests
    class TestKeysetDataSource(private val data: List<Item>) : KeysetDataSource<Item, Long> {
        override suspend fun fetchAfter(key: Long?, limit: Int): List<Item> {
            return if (key == null) data.take(limit)
            else data.filter { it.id > key }.take(limit)
        }

        override suspend fun fetchBefore(key: Long?, limit: Int): List<Item> {
            return if (key == null) data.takeLast(limit)
            else data.filter { it.id < key }.takeLast(limit)
        }

        override fun getKey(item: Item): Long = item.id
    }

    @Test
    fun `keyset - returns first page`() = runBlocking {
        val dataSource = TestKeysetDataSource(testData)
        val paginator = KeysetPaginator(dataSource)
        
        val page = paginator.getPage(KeysetRequest(limit = 10))
        
        assertEquals(10, page.items.size)
        assertEquals(1L, page.items.first().id)
        assertTrue(page.hasNext)
        assertFalse(page.hasPrevious)
    }

    @Test
    fun `keyset - fetches after key`() = runBlocking {
        val dataSource = TestKeysetDataSource(testData)
        val paginator = KeysetPaginator(dataSource)
        
        val page = paginator.getPage(KeysetRequest(limit = 10, afterKey = 10L))
        
        assertEquals(11L, page.items.first().id)
        assertTrue(page.hasNext)
        assertTrue(page.hasPrevious)
    }

    @Test
    fun `keyset - iterates all pages`() = runBlocking {
        val dataSource = TestKeysetDataSource(testData)
        val paginator = KeysetPaginator(dataSource)
        
        val pages = paginator.getAllPages(pageSize = 10).toList()
        
        assertEquals(5, pages.size)
        assertEquals(50, pages.flatMap { it.items }.size)
    }

    @Test
    fun `keyset - backward pagination`() = runBlocking {
        val dataSource = TestKeysetDataSource(testData)
        val paginator = KeysetPaginator(dataSource)
        
        val page = paginator.getPage(
            KeysetRequest(limit = 10, beforeKey = 41L, direction = KeysetRequest.Direction.BACKWARD)
        )
        
        assertEquals(10, page.items.size)
        assertTrue(page.items.all { it.id < 41L })
    }
}
