package com.systemdesign.librarymanagement.approach_01_strategy_search

import com.systemdesign.librarymanagement.common.*

/**
 * Approach 1: Strategy Pattern for Search
 * 
 * Different search algorithms are encapsulated as strategies that can be
 * swapped at runtime. This allows flexible search capabilities.
 * 
 * Pattern: Strategy
 * 
 * Trade-offs:
 * + Easy to add new search strategies without modifying existing code
 * + Strategies can be combined or chained
 * + Clean separation of search logic from library management
 * - Each strategy is a separate class
 * - May need to inject dependencies into strategies
 * 
 * When to use:
 * - When you need multiple interchangeable algorithms
 * - When algorithms might change or be extended frequently
 * - When you want to isolate algorithm logic for testing
 * 
 * Extensibility:
 * - New search type: Implement SearchStrategy interface
 * - Combine searches: Use CompositeSearchStrategy
 */

interface SearchStrategy {
    fun search(query: String, catalog: BookCatalog): List<Book>
    val name: String
}

class TitleSearchStrategy(
    private val matchType: MatchType = MatchType.CONTAINS
) : SearchStrategy {
    override val name = "Title Search"
    
    override fun search(query: String, catalog: BookCatalog): List<Book> {
        val normalizedQuery = query.lowercase().trim()
        return catalog.getAllBooks().filter { book ->
            when (matchType) {
                MatchType.EXACT -> book.title.lowercase() == normalizedQuery
                MatchType.STARTS_WITH -> book.title.lowercase().startsWith(normalizedQuery)
                MatchType.CONTAINS -> book.title.lowercase().contains(normalizedQuery)
                MatchType.FUZZY -> fuzzyMatch(book.title.lowercase(), normalizedQuery)
            }
        }
    }
}

class AuthorSearchStrategy(
    private val matchType: MatchType = MatchType.CONTAINS
) : SearchStrategy {
    override val name = "Author Search"
    
    override fun search(query: String, catalog: BookCatalog): List<Book> {
        val normalizedQuery = query.lowercase().trim()
        return catalog.getAllBooks().filter { book ->
            when (matchType) {
                MatchType.EXACT -> book.author.lowercase() == normalizedQuery
                MatchType.STARTS_WITH -> book.author.lowercase().startsWith(normalizedQuery)
                MatchType.CONTAINS -> book.author.lowercase().contains(normalizedQuery)
                MatchType.FUZZY -> fuzzyMatch(book.author.lowercase(), normalizedQuery)
            }
        }
    }
}

class ISBNSearchStrategy : SearchStrategy {
    override val name = "ISBN Search"
    
    override fun search(query: String, catalog: BookCatalog): List<Book> {
        val normalizedQuery = query.replace("-", "").replace(" ", "").trim()
        return catalog.getAllBooks().filter { book ->
            val normalizedIsbn = book.isbn.replace("-", "").replace(" ", "")
            normalizedIsbn == normalizedQuery || normalizedIsbn.contains(normalizedQuery)
        }
    }
}

class GenreSearchStrategy(
    private val matchType: MatchType = MatchType.CONTAINS
) : SearchStrategy {
    override val name = "Genre Search"
    
    override fun search(query: String, catalog: BookCatalog): List<Book> {
        val normalizedQuery = query.lowercase().trim()
        return catalog.getAllBooks().filter { book ->
            when (matchType) {
                MatchType.EXACT -> book.genre.lowercase() == normalizedQuery
                MatchType.STARTS_WITH -> book.genre.lowercase().startsWith(normalizedQuery)
                MatchType.CONTAINS -> book.genre.lowercase().contains(normalizedQuery)
                MatchType.FUZZY -> fuzzyMatch(book.genre.lowercase(), normalizedQuery)
            }
        }
    }
}

class AvailabilitySearchStrategy(
    private val baseStrategy: SearchStrategy
) : SearchStrategy {
    override val name = "Available ${baseStrategy.name}"
    
    override fun search(query: String, catalog: BookCatalog): List<Book> {
        return baseStrategy.search(query, catalog).filter { it.isAvailable() }
    }
}

class CompositeSearchStrategy(
    private val strategies: List<SearchStrategy>,
    private val combineMode: CombineMode = CombineMode.UNION
) : SearchStrategy {
    override val name = "Composite Search (${strategies.joinToString(", ") { it.name }})"
    
    override fun search(query: String, catalog: BookCatalog): List<Book> {
        if (strategies.isEmpty()) return emptyList()
        
        val results = strategies.map { it.search(query, catalog).toSet() }
        
        return when (combineMode) {
            CombineMode.UNION -> results.reduce { acc, set -> acc.union(set) }.toList()
            CombineMode.INTERSECTION -> results.reduce { acc, set -> acc.intersect(set) }.toList()
        }
    }
}

class MultiFieldSearchStrategy : SearchStrategy {
    override val name = "Multi-Field Search"
    
    override fun search(query: String, catalog: BookCatalog): List<Book> {
        val normalizedQuery = query.lowercase().trim()
        return catalog.getAllBooks().filter { book ->
            book.title.lowercase().contains(normalizedQuery) ||
                book.author.lowercase().contains(normalizedQuery) ||
                book.genre.lowercase().contains(normalizedQuery) ||
                book.isbn.replace("-", "").contains(normalizedQuery.replace("-", ""))
        }.sortedByDescending { book ->
            calculateRelevanceScore(book, normalizedQuery)
        }
    }
    
    private fun calculateRelevanceScore(book: Book, query: String): Int {
        var score = 0
        if (book.title.lowercase() == query) score += 100
        if (book.title.lowercase().startsWith(query)) score += 50
        if (book.title.lowercase().contains(query)) score += 25
        if (book.author.lowercase() == query) score += 80
        if (book.author.lowercase().contains(query)) score += 20
        if (book.isbn.replace("-", "") == query.replace("-", "")) score += 100
        if (book.genre.lowercase() == query) score += 30
        return score
    }
}

enum class MatchType {
    EXACT,
    STARTS_WITH,
    CONTAINS,
    FUZZY
}

enum class CombineMode {
    UNION,
    INTERSECTION
}

private fun fuzzyMatch(text: String, query: String, threshold: Double = 0.6): Boolean {
    if (text.contains(query)) return true
    
    val distance = levenshteinDistance(text, query)
    val maxLength = maxOf(text.length, query.length)
    val similarity = 1.0 - (distance.toDouble() / maxLength)
    
    return similarity >= threshold
}

private fun levenshteinDistance(s1: String, s2: String): Int {
    val m = s1.length
    val n = s2.length
    val dp = Array(m + 1) { IntArray(n + 1) }
    
    for (i in 0..m) dp[i][0] = i
    for (j in 0..n) dp[0][j] = j
    
    for (i in 1..m) {
        for (j in 1..n) {
            dp[i][j] = if (s1[i - 1] == s2[j - 1]) {
                dp[i - 1][j - 1]
            } else {
                minOf(dp[i - 1][j - 1], dp[i - 1][j], dp[i][j - 1]) + 1
            }
        }
    }
    return dp[m][n]
}

class SearchableLibrary(
    private val catalog: BookCatalog = BookCatalog(),
    private var defaultStrategy: SearchStrategy = MultiFieldSearchStrategy()
) {
    fun setDefaultStrategy(strategy: SearchStrategy) {
        defaultStrategy = strategy
    }
    
    fun search(query: String): List<Book> {
        return defaultStrategy.search(query, catalog)
    }
    
    fun searchWith(query: String, strategy: SearchStrategy): List<Book> {
        return strategy.search(query, catalog)
    }
    
    fun advancedSearch(
        title: String? = null,
        author: String? = null,
        isbn: String? = null,
        genre: String? = null,
        availableOnly: Boolean = false
    ): List<Book> {
        var results = catalog.getAllBooks()
        
        title?.let { t ->
            results = results.filter { it.title.lowercase().contains(t.lowercase()) }
        }
        author?.let { a ->
            results = results.filter { it.author.lowercase().contains(a.lowercase()) }
        }
        isbn?.let { i ->
            results = results.filter { 
                it.isbn.replace("-", "").contains(i.replace("-", "")) 
            }
        }
        genre?.let { g ->
            results = results.filter { it.genre.lowercase().contains(g.lowercase()) }
        }
        if (availableOnly) {
            results = results.filter { it.isAvailable() }
        }
        
        return results
    }
    
    fun getCatalog(): BookCatalog = catalog
    
    fun addBook(book: Book) {
        catalog.addBook(book)
    }
    
    fun getBook(isbn: String): Book? = catalog.getBook(isbn)
}
