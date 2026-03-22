package com.systemdesign.librarymanagement.approach_02_state_machine

import com.systemdesign.librarymanagement.common.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Approach 2: State Machine Pattern for Book Lifecycle
 * 
 * The book lifecycle is modeled as an explicit finite state machine.
 * Each state has specific valid operations and transitions.
 * 
 * Pattern: State Machine
 * 
 * Trade-offs:
 * + Clear state transitions prevent invalid operations
 * + Easy to reason about book lifecycle
 * + State-specific business rules enforcement
 * + Audit trail of state changes
 * - More boilerplate for state management
 * - State explosion if too many orthogonal concerns
 * 
 * When to use:
 * - When entities have clear discrete states
 * - When operations are only valid in certain states
 * - When audit/logging of state transitions is important
 * 
 * Extensibility:
 * - New state: Add to BookStatus enum and update transition table
 * - New transition: Add handler and update valid transitions
 */

class StateMachineLibrary(
    private val catalog: BookCatalog = BookCatalog(),
    private val finePolicy: FinePolicy = FinePolicy(),
    private val loanPolicy: LoanPolicy = LoanPolicy()
) {
    private val loans = ConcurrentHashMap<String, Loan>()
    private val activeLoans = ConcurrentHashMap<String, Loan>()
    private val reservations = ConcurrentHashMap<String, Reservation>()
    private val memberLoans = ConcurrentHashMap<String, MutableList<Loan>>()
    private val fines = ConcurrentHashMap<String, MutableList<Fine>>()
    private val waitlist = ConcurrentHashMap<String, MutableList<Member>>()
    
    private val validTransitions = mapOf(
        BookStatus.AVAILABLE to setOf(
            BookStatus.RESERVED,
            BookStatus.BORROWED,
            BookStatus.RETIRED
        ),
        BookStatus.RESERVED to setOf(
            BookStatus.AVAILABLE,
            BookStatus.BORROWED,
            BookStatus.RETIRED
        ),
        BookStatus.BORROWED to setOf(
            BookStatus.AVAILABLE,
            BookStatus.OVERDUE,
            BookStatus.LOST
        ),
        BookStatus.OVERDUE to setOf(
            BookStatus.AVAILABLE,
            BookStatus.LOST
        ),
        BookStatus.LOST to setOf(
            BookStatus.AVAILABLE,
            BookStatus.RETIRED
        ),
        BookStatus.RETIRED to emptySet()
    )
    
    private fun canTransition(book: Book, to: BookStatus): Boolean {
        return validTransitions[book.status]?.contains(to) == true
    }
    
    private fun transition(book: Book, to: BookStatus): Boolean {
        if (!canTransition(book, to)) return false
        book.status = to
        return true
    }
    
    fun addBook(book: Book) {
        catalog.addBook(book)
    }
    
    fun getBook(isbn: String): Book? = catalog.getBook(isbn)
    
    fun reserve(isbn: String, member: Member, currentTime: LocalDateTime = LocalDateTime.now()): LibraryResult {
        val book = catalog.getBook(isbn) 
            ?: return LibraryResult.Error("Book not found: $isbn")
        
        // If book is borrowed/overdue, add to waitlist (don't block reservation request)
        if (book.status == BookStatus.BORROWED || book.status == BookStatus.OVERDUE) {
            addToWaitlist(isbn, member)
            return LibraryResult.Success("Added to waitlist for book: ${book.title}")
        }
        
        // Check valid transitions only if we're actually trying to reserve (not waitlist)
        if (!canTransition(book, BookStatus.RESERVED) && book.status != BookStatus.RESERVED) {
            return LibraryResult.InvalidTransition(book.status, "reserve")
        }
        
        if (book.status == BookStatus.RESERVED) {
            val existingReservation = reservations.values.find { 
                it.book.isbn == isbn && it.isActive(currentTime) 
            }
            if (existingReservation != null && existingReservation.member.id != member.id) {
                addToWaitlist(isbn, member)
                return LibraryResult.Success("Book already reserved. Added to waitlist.")
            }
        }
        
        transition(book, BookStatus.RESERVED)
        
        val reservation = Reservation(
            id = UUID.randomUUID().toString(),
            book = book,
            member = member,
            reservedAt = currentTime,
            expiresAt = currentTime.plusDays(loanPolicy.reservationHoldDays.toLong())
        )
        
        reservations[reservation.id] = reservation
        
        return LibraryResult.BookReserved(reservation)
    }
    
    fun checkout(
        isbn: String, 
        member: Member, 
        currentDate: LocalDate = LocalDate.now()
    ): LibraryResult {
        val book = catalog.getBook(isbn) 
            ?: return LibraryResult.Error("Book not found: $isbn")
        
        val memberLoanCount = memberLoans[member.id]?.count { !it.isReturned() } ?: 0
        if (memberLoanCount >= loanPolicy.maxLoansPerMember) {
            return LibraryResult.Error("Member has reached maximum loans (${loanPolicy.maxLoansPerMember})")
        }
        
        val hasUnpaidFines = fines[member.id]?.any { !it.paid } == true
        if (hasUnpaidFines) {
            return LibraryResult.Error("Member has unpaid fines")
        }
        
        when (book.status) {
            BookStatus.RESERVED -> {
                val reservation = reservations.values.find { 
                    it.book.isbn == isbn && it.member.id == member.id && it.isActive() 
                }
                if (reservation == null) {
                    return LibraryResult.Error("Book is reserved by another member")
                }
                reservations.remove(reservation.id)
            }
            BookStatus.AVAILABLE -> {}
            else -> return LibraryResult.InvalidTransition(book.status, "checkout")
        }
        
        transition(book, BookStatus.BORROWED)
        
        val loan = Loan(
            id = UUID.randomUUID().toString(),
            book = book,
            member = member,
            checkoutDate = currentDate,
            dueDate = currentDate.plusDays(loanPolicy.standardLoanDays.toLong())
        )
        
        loans[loan.id] = loan
        activeLoans[loan.id] = loan
        memberLoans.getOrPut(member.id) { mutableListOf() }.add(loan)
        
        return LibraryResult.BookCheckedOut(loan)
    }
    
    fun returnBook(
        isbn: String, 
        currentDate: LocalDate = LocalDate.now()
    ): LibraryResult {
        val book = catalog.getBook(isbn) 
            ?: return LibraryResult.Error("Book not found: $isbn")
        
        if (book.status != BookStatus.BORROWED && book.status != BookStatus.OVERDUE) {
            return LibraryResult.InvalidTransition(book.status, "return")
        }
        
        val loan = activeLoans.values.find { it.book.isbn == isbn }
            ?: return LibraryResult.Error("No active loan found for book: $isbn")
        
        // Check overdue BEFORE setting return date
        var fine: Fine? = null
        if (currentDate.isAfter(loan.dueDate)) {
            val daysOverdue = ChronoUnit.DAYS.between(loan.dueDate, currentDate)
            val fineAmount = finePolicy.calculateFine(daysOverdue)
            if (fineAmount > 0) {
                fine = Fine(loan.member, loan, fineAmount)
                fines.getOrPut(loan.member.id) { mutableListOf() }.add(fine)
            }
        }
        
        loan.returnDate = currentDate
        activeLoans.remove(loan.id)
        
        transition(book, BookStatus.AVAILABLE)
        
        processWaitlist(isbn)
        
        return LibraryResult.BookReturned(loan, fine)
    }
    
    fun markOverdue(isbn: String): LibraryResult {
        val book = catalog.getBook(isbn)
            ?: return LibraryResult.Error("Book not found: $isbn")
        
        if (book.status != BookStatus.BORROWED) {
            return LibraryResult.InvalidTransition(book.status, "markOverdue")
        }
        
        transition(book, BookStatus.OVERDUE)
        return LibraryResult.Success("Book marked as overdue: ${book.title}")
    }
    
    fun markLost(isbn: String, currentDate: LocalDate = LocalDate.now()): LibraryResult {
        val book = catalog.getBook(isbn)
            ?: return LibraryResult.Error("Book not found: $isbn")
        
        if (!canTransition(book, BookStatus.LOST)) {
            return LibraryResult.InvalidTransition(book.status, "markLost")
        }
        
        val loan = activeLoans.values.find { it.book.isbn == isbn }
        if (loan != null) {
            loan.returnDate = currentDate
            activeLoans.remove(loan.id)
            
            val lostBookFine = Fine(loan.member, loan, finePolicy.maxFine * 2)
            fines.getOrPut(loan.member.id) { mutableListOf() }.add(lostBookFine)
        }
        
        transition(book, BookStatus.LOST)
        
        return LibraryResult.Success("Book marked as lost: ${book.title}")
    }
    
    fun foundLostBook(isbn: String): LibraryResult {
        val book = catalog.getBook(isbn)
            ?: return LibraryResult.Error("Book not found: $isbn")
        
        if (book.status != BookStatus.LOST) {
            return LibraryResult.InvalidTransition(book.status, "foundLostBook")
        }
        
        transition(book, BookStatus.AVAILABLE)
        
        processWaitlist(isbn)
        
        return LibraryResult.Success("Lost book found and returned to circulation: ${book.title}")
    }
    
    fun retire(isbn: String): LibraryResult {
        val book = catalog.getBook(isbn)
            ?: return LibraryResult.Error("Book not found: $isbn")
        
        if (!canTransition(book, BookStatus.RETIRED)) {
            return LibraryResult.InvalidTransition(book.status, "retire")
        }
        
        transition(book, BookStatus.RETIRED)
        
        reservations.values
            .filter { it.book.isbn == isbn }
            .forEach { reservations.remove(it.id) }
        
        waitlist.remove(isbn)
        
        return LibraryResult.Success("Book retired from circulation: ${book.title}")
    }
    
    fun cancelReservation(reservationId: String): LibraryResult {
        val reservation = reservations.remove(reservationId)
            ?: return LibraryResult.Error("Reservation not found: $reservationId")
        
        val book = reservation.book
        
        val otherActiveReservations = reservations.values.any { 
            it.book.isbn == book.isbn && it.isActive() 
        }
        
        if (!otherActiveReservations && book.status == BookStatus.RESERVED) {
            transition(book, BookStatus.AVAILABLE)
            processWaitlist(book.isbn)
        }
        
        return LibraryResult.Success("Reservation cancelled for: ${book.title}")
    }
    
    fun checkAndUpdateOverdueBooks(currentDate: LocalDate = LocalDate.now()): List<Loan> {
        val newlyOverdue = mutableListOf<Loan>()
        
        activeLoans.values
            .filter { it.book.status == BookStatus.BORROWED && it.isOverdue(currentDate) }
            .forEach { loan ->
                if (transition(loan.book, BookStatus.OVERDUE)) {
                    newlyOverdue.add(loan)
                }
            }
        
        return newlyOverdue
    }
    
    fun expireReservations(currentTime: LocalDateTime = LocalDateTime.now()): List<Reservation> {
        val expired = mutableListOf<Reservation>()
        
        reservations.values
            .filter { it.isExpired(currentTime) }
            .forEach { reservation ->
                reservations.remove(reservation.id)
                expired.add(reservation)
                
                val book = reservation.book
                val hasOtherReservations = reservations.values.any { 
                    it.book.isbn == book.isbn && it.isActive(currentTime) 
                }
                
                if (!hasOtherReservations && book.status == BookStatus.RESERVED) {
                    transition(book, BookStatus.AVAILABLE)
                    processWaitlist(book.isbn)
                }
            }
        
        return expired
    }
    
    private fun addToWaitlist(isbn: String, member: Member) {
        val list = waitlist.getOrPut(isbn) { mutableListOf() }
        if (list.none { it.id == member.id }) {
            list.add(member)
        }
    }
    
    private fun processWaitlist(isbn: String): Member? {
        val list = waitlist[isbn] ?: return null
        return list.removeFirstOrNull()
    }
    
    fun getWaitlist(isbn: String): List<Member> = 
        waitlist[isbn]?.toList() ?: emptyList()
    
    fun getNextInWaitlist(isbn: String): Member? = 
        waitlist[isbn]?.firstOrNull()
    
    fun payFine(fineId: String, memberId: String): LibraryResult {
        val memberFines = fines[memberId] 
            ?: return LibraryResult.Error("No fines found for member")
        
        val fine = memberFines.find { it.loan.id == fineId && !it.paid }
            ?: return LibraryResult.Error("Fine not found or already paid")
        
        fine.paid = true
        
        return LibraryResult.Success("Fine paid: $${fine.amount}")
    }
    
    fun getUnpaidFines(memberId: String): List<Fine> =
        fines[memberId]?.filter { !it.paid } ?: emptyList()
    
    fun getTotalUnpaidFines(memberId: String): Double =
        getUnpaidFines(memberId).sumOf { it.amount }
    
    fun getMemberLoans(memberId: String): List<Loan> =
        memberLoans[memberId]?.toList() ?: emptyList()
    
    fun getActiveLoans(memberId: String): List<Loan> =
        memberLoans[memberId]?.filter { !it.isReturned() } ?: emptyList()
    
    fun getAllActiveLoans(): List<Loan> = activeLoans.values.toList()
    
    fun getOverdueLoans(): List<Loan> = 
        activeLoans.values.filter { it.book.status == BookStatus.OVERDUE }
    
    fun getReservation(reservationId: String): Reservation? = reservations[reservationId]
    
    fun getMemberReservations(memberId: String): List<Reservation> =
        reservations.values.filter { it.member.id == memberId }
    
    fun getCatalog(): BookCatalog = catalog
    
    fun calculateFine(loan: Loan, currentDate: LocalDate = LocalDate.now()): Double {
        if (!loan.isOverdue(currentDate)) return 0.0
        return finePolicy.calculateFine(loan.getDaysOverdue(currentDate))
    }
}
