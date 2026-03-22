package com.systemdesign.librarymanagement.common

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

enum class BookStatus {
    AVAILABLE,
    RESERVED,
    BORROWED,
    OVERDUE,
    LOST,
    RETIRED
}

data class Book(
    val isbn: String,
    val title: String,
    val author: String,
    val genre: String,
    var status: BookStatus = BookStatus.AVAILABLE
) {
    fun isAvailable(): Boolean = status == BookStatus.AVAILABLE
    fun isBorrowed(): Boolean = status == BookStatus.BORROWED || status == BookStatus.OVERDUE
}

data class Member(
    val id: String,
    val name: String,
    val email: String,
    val memberSince: LocalDate
) {
    fun getMembershipDurationDays(): Long = ChronoUnit.DAYS.between(memberSince, LocalDate.now())
}

data class Loan(
    val id: String,
    val book: Book,
    val member: Member,
    val checkoutDate: LocalDate,
    val dueDate: LocalDate,
    var returnDate: LocalDate? = null
) {
    fun isOverdue(currentDate: LocalDate = LocalDate.now()): Boolean =
        returnDate == null && currentDate.isAfter(dueDate)
    
    fun getDaysOverdue(currentDate: LocalDate = LocalDate.now()): Long =
        if (isOverdue(currentDate)) ChronoUnit.DAYS.between(dueDate, currentDate) else 0
    
    fun isReturned(): Boolean = returnDate != null
}

data class Reservation(
    val id: String,
    val book: Book,
    val member: Member,
    val reservedAt: LocalDateTime,
    val expiresAt: LocalDateTime
) {
    fun isExpired(currentTime: LocalDateTime = LocalDateTime.now()): Boolean =
        currentTime.isAfter(expiresAt)
    
    fun isActive(currentTime: LocalDateTime = LocalDateTime.now()): Boolean =
        !isExpired(currentTime)
}

data class Fine(
    val member: Member,
    val loan: Loan,
    val amount: Double,
    var paid: Boolean = false
)

data class BookCatalog(
    private val books: MutableList<Book> = mutableListOf()
) {
    fun addBook(book: Book) {
        books.add(book)
    }
    
    fun removeBook(isbn: String): Book? {
        val book = books.find { it.isbn == isbn }
        book?.let { books.remove(it) }
        return book
    }
    
    fun getBook(isbn: String): Book? = books.find { it.isbn == isbn }
    
    fun getAllBooks(): List<Book> = books.toList()
    
    fun getAvailableBooks(): List<Book> = books.filter { it.isAvailable() }
    
    fun getBooksByStatus(status: BookStatus): List<Book> = books.filter { it.status == status }
    
    fun size(): Int = books.size
}

sealed class LibraryResult {
    data class Success(val message: String) : LibraryResult()
    data class BookCheckedOut(val loan: Loan) : LibraryResult()
    data class BookReturned(val loan: Loan, val fine: Fine?) : LibraryResult()
    data class BookReserved(val reservation: Reservation) : LibraryResult()
    data class SearchResults(val books: List<Book>) : LibraryResult()
    data class Error(val message: String) : LibraryResult()
    data class InvalidTransition(val from: BookStatus, val action: String) : LibraryResult()
}

data class FinePolicy(
    val dailyRate: Double = 0.50,
    val maxFine: Double = 25.00,
    val gracePeriodDays: Int = 0
) {
    fun calculateFine(daysOverdue: Long): Double {
        val chargeableDays = maxOf(0L, daysOverdue - gracePeriodDays)
        return minOf(chargeableDays * dailyRate, maxFine)
    }
}

data class LoanPolicy(
    val standardLoanDays: Int = 14,
    val maxRenewals: Int = 2,
    val maxLoansPerMember: Int = 5,
    val reservationHoldDays: Int = 3
)
