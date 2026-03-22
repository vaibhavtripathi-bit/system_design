package com.systemdesign.librarymanagement.approach_03_observer_notifications

import com.systemdesign.librarymanagement.common.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Approach 3: Observer Pattern for Notifications
 * 
 * Library events are published to observers who can react to them.
 * This decouples the library operations from notification logic.
 * 
 * Pattern: Observer (Pub/Sub)
 * 
 * Trade-offs:
 * + Clean separation between core logic and notifications
 * + Easy to add new notification channels
 * + Observers can be added/removed dynamically
 * + Events can be logged, filtered, or transformed
 * - Potential for cascade failures if observer throws
 * - Harder to trace event flow during debugging
 * - Memory leaks if observers not properly removed
 * 
 * When to use:
 * - When multiple components need to react to the same events
 * - When notification requirements change frequently
 * - When you need audit trails or event logging
 * 
 * Extensibility:
 * - New notification type: Add method to LibraryObserver
 * - New notification channel: Implement LibraryObserver
 */

interface LibraryObserver {
    fun onBookReserved(reservation: Reservation)
    fun onBookCheckedOut(loan: Loan)
    fun onBookReturned(loan: Loan, fine: Fine?)
    fun onDueDateApproaching(loan: Loan, daysRemaining: Int)
    fun onBookOverdue(loan: Loan, daysOverdue: Long)
    fun onReservationExpiring(reservation: Reservation, hoursRemaining: Int)
    fun onBookAvailable(book: Book, nextInWaitlist: Member?)
    fun onFineCreated(fine: Fine)
    fun onFinePaid(fine: Fine)
}

abstract class BaseLibraryObserver : LibraryObserver {
    override fun onBookReserved(reservation: Reservation) {}
    override fun onBookCheckedOut(loan: Loan) {}
    override fun onBookReturned(loan: Loan, fine: Fine?) {}
    override fun onDueDateApproaching(loan: Loan, daysRemaining: Int) {}
    override fun onBookOverdue(loan: Loan, daysOverdue: Long) {}
    override fun onReservationExpiring(reservation: Reservation, hoursRemaining: Int) {}
    override fun onBookAvailable(book: Book, nextInWaitlist: Member?) {}
    override fun onFineCreated(fine: Fine) {}
    override fun onFinePaid(fine: Fine) {}
}

class EmailNotifier(
    private val emailService: EmailService = ConsoleEmailService()
) : BaseLibraryObserver() {
    
    override fun onBookReserved(reservation: Reservation) {
        emailService.sendEmail(
            to = reservation.member.email,
            subject = "Book Reserved: ${reservation.book.title}",
            body = """
                Dear ${reservation.member.name},
                
                Your reservation for "${reservation.book.title}" by ${reservation.book.author} 
                has been confirmed.
                
                Reservation ID: ${reservation.id}
                Reserved until: ${reservation.expiresAt}
                
                Please pick up the book before the reservation expires.
                
                Thank you,
                Library Management System
            """.trimIndent()
        )
    }
    
    override fun onBookCheckedOut(loan: Loan) {
        emailService.sendEmail(
            to = loan.member.email,
            subject = "Book Checked Out: ${loan.book.title}",
            body = """
                Dear ${loan.member.name},
                
                You have checked out "${loan.book.title}" by ${loan.book.author}.
                
                Loan ID: ${loan.id}
                Checkout Date: ${loan.checkoutDate}
                Due Date: ${loan.dueDate}
                
                Please return the book by the due date to avoid late fees.
                
                Thank you,
                Library Management System
            """.trimIndent()
        )
    }
    
    override fun onDueDateApproaching(loan: Loan, daysRemaining: Int) {
        emailService.sendEmail(
            to = loan.member.email,
            subject = "Reminder: Book Due Soon - ${loan.book.title}",
            body = """
                Dear ${loan.member.name},
                
                This is a reminder that "${loan.book.title}" is due in $daysRemaining day(s).
                
                Due Date: ${loan.dueDate}
                
                Please return the book on time to avoid late fees.
                
                Thank you,
                Library Management System
            """.trimIndent()
        )
    }
    
    override fun onBookOverdue(loan: Loan, daysOverdue: Long) {
        emailService.sendEmail(
            to = loan.member.email,
            subject = "OVERDUE: ${loan.book.title}",
            body = """
                Dear ${loan.member.name},
                
                "${loan.book.title}" is now $daysOverdue day(s) overdue.
                
                Due Date: ${loan.dueDate}
                
                Please return the book as soon as possible. Late fees are being applied.
                
                Thank you,
                Library Management System
            """.trimIndent()
        )
    }
    
    override fun onBookAvailable(book: Book, nextInWaitlist: Member?) {
        nextInWaitlist?.let { member ->
            emailService.sendEmail(
                to = member.email,
                subject = "Book Now Available: ${book.title}",
                body = """
                    Dear ${member.name},
                    
                    Great news! "${book.title}" by ${book.author} is now available.
                    
                    You were on the waitlist for this book. Please visit the library 
                    to borrow it within the next 3 days.
                    
                    Thank you,
                    Library Management System
                """.trimIndent()
            )
        }
    }
    
    override fun onFineCreated(fine: Fine) {
        emailService.sendEmail(
            to = fine.member.email,
            subject = "Library Fine Notice",
            body = """
                Dear ${fine.member.name},
                
                A fine of $${String.format("%.2f", fine.amount)} has been applied to your account.
                
                Book: ${fine.loan.book.title}
                Due Date: ${fine.loan.dueDate}
                Return Date: ${fine.loan.returnDate ?: "Not returned"}
                
                Please pay your fine at the library or online.
                
                Thank you,
                Library Management System
            """.trimIndent()
        )
    }
}

class SystemNotifier : BaseLibraryObserver() {
    data class Notification(
        val id: String = UUID.randomUUID().toString(),
        val memberId: String,
        val type: NotificationType,
        val title: String,
        val message: String,
        val createdAt: LocalDateTime = LocalDateTime.now(),
        var read: Boolean = false
    )
    
    enum class NotificationType {
        RESERVATION, CHECKOUT, RETURN, DUE_SOON, OVERDUE, AVAILABLE, FINE
    }
    
    private val notifications = ConcurrentHashMap<String, MutableList<Notification>>()
    
    override fun onBookReserved(reservation: Reservation) {
        addNotification(
            memberId = reservation.member.id,
            type = NotificationType.RESERVATION,
            title = "Book Reserved",
            message = "Your reservation for '${reservation.book.title}' is confirmed. " +
                "Pick up by ${reservation.expiresAt.toLocalDate()}."
        )
    }
    
    override fun onBookCheckedOut(loan: Loan) {
        addNotification(
            memberId = loan.member.id,
            type = NotificationType.CHECKOUT,
            title = "Book Checked Out",
            message = "'${loan.book.title}' is due on ${loan.dueDate}."
        )
    }
    
    override fun onBookReturned(loan: Loan, fine: Fine?) {
        val message = if (fine != null) {
            "'${loan.book.title}' returned. Fine: $${String.format("%.2f", fine.amount)}"
        } else {
            "'${loan.book.title}' returned successfully."
        }
        addNotification(
            memberId = loan.member.id,
            type = NotificationType.RETURN,
            title = "Book Returned",
            message = message
        )
    }
    
    override fun onDueDateApproaching(loan: Loan, daysRemaining: Int) {
        addNotification(
            memberId = loan.member.id,
            type = NotificationType.DUE_SOON,
            title = "Due Date Approaching",
            message = "'${loan.book.title}' is due in $daysRemaining day(s)."
        )
    }
    
    override fun onBookOverdue(loan: Loan, daysOverdue: Long) {
        addNotification(
            memberId = loan.member.id,
            type = NotificationType.OVERDUE,
            title = "Book Overdue",
            message = "'${loan.book.title}' is $daysOverdue day(s) overdue. Please return immediately."
        )
    }
    
    override fun onBookAvailable(book: Book, nextInWaitlist: Member?) {
        nextInWaitlist?.let { member ->
            addNotification(
                memberId = member.id,
                type = NotificationType.AVAILABLE,
                title = "Book Available",
                message = "'${book.title}' is now available. Reserve within 3 days."
            )
        }
    }
    
    override fun onFineCreated(fine: Fine) {
        addNotification(
            memberId = fine.member.id,
            type = NotificationType.FINE,
            title = "Fine Applied",
            message = "A fine of $${String.format("%.2f", fine.amount)} was applied for '${fine.loan.book.title}'."
        )
    }
    
    private fun addNotification(memberId: String, type: NotificationType, title: String, message: String) {
        notifications.getOrPut(memberId) { mutableListOf() }.add(
            Notification(memberId = memberId, type = type, title = title, message = message)
        )
    }
    
    fun getNotifications(memberId: String): List<Notification> =
        notifications[memberId]?.toList() ?: emptyList()
    
    fun getUnreadNotifications(memberId: String): List<Notification> =
        notifications[memberId]?.filter { !it.read } ?: emptyList()
    
    fun markAsRead(notificationId: String, memberId: String) {
        notifications[memberId]?.find { it.id == notificationId }?.read = true
    }
    
    fun markAllAsRead(memberId: String) {
        notifications[memberId]?.forEach { it.read = true }
    }
    
    fun clearNotifications(memberId: String) {
        notifications.remove(memberId)
    }
}

class OverdueReporter : BaseLibraryObserver() {
    data class OverdueRecord(
        val loan: Loan,
        val daysOverdue: Long,
        val estimatedFine: Double,
        val reportedAt: LocalDateTime = LocalDateTime.now()
    )
    
    private val overdueRecords = CopyOnWriteArrayList<OverdueRecord>()
    
    override fun onBookOverdue(loan: Loan, daysOverdue: Long) {
        val existingRecord = overdueRecords.find { it.loan.id == loan.id }
        if (existingRecord != null) {
            overdueRecords.remove(existingRecord)
        }
        
        val estimatedFine = FinePolicy().calculateFine(daysOverdue)
        overdueRecords.add(OverdueRecord(loan, daysOverdue, estimatedFine))
    }
    
    override fun onBookReturned(loan: Loan, fine: Fine?) {
        overdueRecords.removeIf { it.loan.id == loan.id }
    }
    
    fun getOverdueReport(): List<OverdueRecord> = overdueRecords.toList()
    
    fun getOverdueByMember(memberId: String): List<OverdueRecord> =
        overdueRecords.filter { it.loan.member.id == memberId }
    
    fun getTotalEstimatedFines(): Double =
        overdueRecords.sumOf { it.estimatedFine }
    
    fun getOverdueCount(): Int = overdueRecords.size
    
    fun clearResolvedRecords() {
        overdueRecords.removeIf { it.loan.isReturned() }
    }
}

class AuditLogger : LibraryObserver {
    data class AuditEntry(
        val id: String = UUID.randomUUID().toString(),
        val timestamp: LocalDateTime = LocalDateTime.now(),
        val eventType: String,
        val entityType: String,
        val entityId: String,
        val memberId: String?,
        val details: Map<String, Any>
    )
    
    private val auditLog = CopyOnWriteArrayList<AuditEntry>()
    
    override fun onBookReserved(reservation: Reservation) {
        log("BOOK_RESERVED", "Reservation", reservation.id, reservation.member.id, mapOf(
            "bookIsbn" to reservation.book.isbn,
            "bookTitle" to reservation.book.title,
            "expiresAt" to reservation.expiresAt.toString()
        ))
    }
    
    override fun onBookCheckedOut(loan: Loan) {
        log("BOOK_CHECKED_OUT", "Loan", loan.id, loan.member.id, mapOf(
            "bookIsbn" to loan.book.isbn,
            "bookTitle" to loan.book.title,
            "dueDate" to loan.dueDate.toString()
        ))
    }
    
    override fun onBookReturned(loan: Loan, fine: Fine?) {
        log("BOOK_RETURNED", "Loan", loan.id, loan.member.id, mapOf(
            "bookIsbn" to loan.book.isbn,
            "bookTitle" to loan.book.title,
            "returnDate" to (loan.returnDate?.toString() ?: "unknown"),
            "fine" to (fine?.amount ?: 0.0)
        ))
    }
    
    override fun onDueDateApproaching(loan: Loan, daysRemaining: Int) {
        log("DUE_DATE_APPROACHING", "Loan", loan.id, loan.member.id, mapOf(
            "bookIsbn" to loan.book.isbn,
            "daysRemaining" to daysRemaining
        ))
    }
    
    override fun onBookOverdue(loan: Loan, daysOverdue: Long) {
        log("BOOK_OVERDUE", "Loan", loan.id, loan.member.id, mapOf(
            "bookIsbn" to loan.book.isbn,
            "daysOverdue" to daysOverdue
        ))
    }
    
    override fun onReservationExpiring(reservation: Reservation, hoursRemaining: Int) {
        log("RESERVATION_EXPIRING", "Reservation", reservation.id, reservation.member.id, mapOf(
            "bookIsbn" to reservation.book.isbn,
            "hoursRemaining" to hoursRemaining
        ))
    }
    
    override fun onBookAvailable(book: Book, nextInWaitlist: Member?) {
        log("BOOK_AVAILABLE", "Book", book.isbn, nextInWaitlist?.id, mapOf(
            "bookTitle" to book.title,
            "notifiedMember" to (nextInWaitlist?.name ?: "none")
        ))
    }
    
    override fun onFineCreated(fine: Fine) {
        log("FINE_CREATED", "Fine", fine.loan.id, fine.member.id, mapOf(
            "amount" to fine.amount,
            "bookIsbn" to fine.loan.book.isbn
        ))
    }
    
    override fun onFinePaid(fine: Fine) {
        log("FINE_PAID", "Fine", fine.loan.id, fine.member.id, mapOf(
            "amount" to fine.amount
        ))
    }
    
    private fun log(eventType: String, entityType: String, entityId: String, memberId: String?, details: Map<String, Any>) {
        auditLog.add(AuditEntry(
            eventType = eventType,
            entityType = entityType,
            entityId = entityId,
            memberId = memberId,
            details = details
        ))
    }
    
    fun getAuditLog(): List<AuditEntry> = auditLog.toList()
    
    fun getAuditLogForMember(memberId: String): List<AuditEntry> =
        auditLog.filter { it.memberId == memberId }
    
    fun getAuditLogByEventType(eventType: String): List<AuditEntry> =
        auditLog.filter { it.eventType == eventType }
    
    fun getAuditLogInRange(from: LocalDateTime, to: LocalDateTime): List<AuditEntry> =
        auditLog.filter { it.timestamp.isAfter(from) && it.timestamp.isBefore(to) }
}

interface EmailService {
    fun sendEmail(to: String, subject: String, body: String)
}

class ConsoleEmailService : EmailService {
    private val sentEmails = CopyOnWriteArrayList<Triple<String, String, String>>()
    
    override fun sendEmail(to: String, subject: String, body: String) {
        sentEmails.add(Triple(to, subject, body))
        println("📧 Email sent to: $to")
        println("   Subject: $subject")
        println("   Body: ${body.take(100)}...")
    }
    
    fun getSentEmails(): List<Triple<String, String, String>> = sentEmails.toList()
    
    fun clearSentEmails() {
        sentEmails.clear()
    }
}

class ObservableLibrary(
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
    
    private val observers = CopyOnWriteArrayList<LibraryObserver>()
    
    fun addObserver(observer: LibraryObserver) {
        observers.add(observer)
    }
    
    fun removeObserver(observer: LibraryObserver) {
        observers.remove(observer)
    }
    
    fun addBook(book: Book) {
        catalog.addBook(book)
    }
    
    fun getBook(isbn: String): Book? = catalog.getBook(isbn)
    
    fun reserve(isbn: String, member: Member, currentTime: LocalDateTime = LocalDateTime.now()): LibraryResult {
        val book = catalog.getBook(isbn) 
            ?: return LibraryResult.Error("Book not found: $isbn")
        
        if (book.status != BookStatus.AVAILABLE) {
            if (book.status == BookStatus.BORROWED || book.status == BookStatus.OVERDUE) {
                addToWaitlist(isbn, member)
                return LibraryResult.Success("Added to waitlist for book: ${book.title}")
            }
            return LibraryResult.InvalidTransition(book.status, "reserve")
        }
        
        book.status = BookStatus.RESERVED
        
        val reservation = Reservation(
            id = UUID.randomUUID().toString(),
            book = book,
            member = member,
            reservedAt = currentTime,
            expiresAt = currentTime.plusDays(loanPolicy.reservationHoldDays.toLong())
        )
        
        reservations[reservation.id] = reservation
        
        notifyBookReserved(reservation)
        
        return LibraryResult.BookReserved(reservation)
    }
    
    fun checkout(isbn: String, member: Member, currentDate: LocalDate = LocalDate.now()): LibraryResult {
        val book = catalog.getBook(isbn) 
            ?: return LibraryResult.Error("Book not found: $isbn")
        
        val memberLoanCount = memberLoans[member.id]?.count { !it.isReturned() } ?: 0
        if (memberLoanCount >= loanPolicy.maxLoansPerMember) {
            return LibraryResult.Error("Member has reached maximum loans")
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
        
        book.status = BookStatus.BORROWED
        
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
        
        notifyBookCheckedOut(loan)
        
        return LibraryResult.BookCheckedOut(loan)
    }
    
    fun returnBook(isbn: String, currentDate: LocalDate = LocalDate.now()): LibraryResult {
        val book = catalog.getBook(isbn) 
            ?: return LibraryResult.Error("Book not found: $isbn")
        
        if (book.status != BookStatus.BORROWED && book.status != BookStatus.OVERDUE) {
            return LibraryResult.InvalidTransition(book.status, "return")
        }
        
        val loan = activeLoans.values.find { it.book.isbn == isbn }
            ?: return LibraryResult.Error("No active loan found for book: $isbn")
        
        // Check overdue BEFORE setting return date (isOverdue checks returnDate == null)
        var fine: Fine? = null
        if (currentDate.isAfter(loan.dueDate)) {
            val daysOverdue = java.time.temporal.ChronoUnit.DAYS.between(loan.dueDate, currentDate)
            val fineAmount = finePolicy.calculateFine(daysOverdue)
            if (fineAmount > 0) {
                fine = Fine(loan.member, loan, fineAmount)
                fines.getOrPut(loan.member.id) { mutableListOf() }.add(fine)
                notifyFineCreated(fine)
            }
        }
        
        loan.returnDate = currentDate
        activeLoans.remove(loan.id)
        
        book.status = BookStatus.AVAILABLE
        
        notifyBookReturned(loan, fine)
        
        val nextMember = processWaitlist(isbn)
        notifyBookAvailable(book, nextMember)
        
        return LibraryResult.BookReturned(loan, fine)
    }
    
    fun checkDueDates(currentDate: LocalDate = LocalDate.now(), reminderDays: Int = 3) {
        activeLoans.values.forEach { loan ->
            val daysUntilDue = java.time.temporal.ChronoUnit.DAYS.between(currentDate, loan.dueDate).toInt()
            
            when {
                daysUntilDue in 1..reminderDays -> {
                    notifyDueDateApproaching(loan, daysUntilDue)
                }
                daysUntilDue < 0 -> {
                    if (loan.book.status != BookStatus.OVERDUE) {
                        loan.book.status = BookStatus.OVERDUE
                    }
                    notifyBookOverdue(loan, -daysUntilDue.toLong())
                }
            }
        }
    }
    
    fun checkReservationExpirations(currentTime: LocalDateTime = LocalDateTime.now(), reminderHours: Int = 24) {
        reservations.values.forEach { reservation ->
            val hoursUntilExpiry = java.time.temporal.ChronoUnit.HOURS.between(currentTime, reservation.expiresAt).toInt()
            
            when {
                hoursUntilExpiry in 1..reminderHours -> {
                    notifyReservationExpiring(reservation, hoursUntilExpiry)
                }
                hoursUntilExpiry <= 0 -> {
                    expireReservation(reservation.id)
                }
            }
        }
    }
    
    fun expireReservation(reservationId: String): LibraryResult {
        val reservation = reservations.remove(reservationId)
            ?: return LibraryResult.Error("Reservation not found")
        
        val book = reservation.book
        if (book.status == BookStatus.RESERVED) {
            book.status = BookStatus.AVAILABLE
            val nextMember = processWaitlist(book.isbn)
            notifyBookAvailable(book, nextMember)
        }
        
        return LibraryResult.Success("Reservation expired: ${reservation.id}")
    }
    
    fun payFine(loanId: String, memberId: String): LibraryResult {
        val memberFines = fines[memberId]
            ?: return LibraryResult.Error("No fines found")
        
        val fine = memberFines.find { it.loan.id == loanId && !it.paid }
            ?: return LibraryResult.Error("Fine not found or already paid")
        
        fine.paid = true
        notifyFinePaid(fine)
        
        return LibraryResult.Success("Fine paid: $${fine.amount}")
    }
    
    private fun addToWaitlist(isbn: String, member: Member) {
        val list = waitlist.getOrPut(isbn) { mutableListOf() }
        if (list.none { it.id == member.id }) {
            list.add(member)
        }
    }
    
    private fun processWaitlist(isbn: String): Member? {
        return waitlist[isbn]?.removeFirstOrNull()
    }
    
    fun getWaitlist(isbn: String): List<Member> = waitlist[isbn]?.toList() ?: emptyList()
    
    private fun notifyBookReserved(reservation: Reservation) {
        observers.forEach { it.onBookReserved(reservation) }
    }
    
    private fun notifyBookCheckedOut(loan: Loan) {
        observers.forEach { it.onBookCheckedOut(loan) }
    }
    
    private fun notifyBookReturned(loan: Loan, fine: Fine?) {
        observers.forEach { it.onBookReturned(loan, fine) }
    }
    
    private fun notifyDueDateApproaching(loan: Loan, daysRemaining: Int) {
        observers.forEach { it.onDueDateApproaching(loan, daysRemaining) }
    }
    
    private fun notifyBookOverdue(loan: Loan, daysOverdue: Long) {
        observers.forEach { it.onBookOverdue(loan, daysOverdue) }
    }
    
    private fun notifyReservationExpiring(reservation: Reservation, hoursRemaining: Int) {
        observers.forEach { it.onReservationExpiring(reservation, hoursRemaining) }
    }
    
    private fun notifyBookAvailable(book: Book, nextInWaitlist: Member?) {
        observers.forEach { it.onBookAvailable(book, nextInWaitlist) }
    }
    
    private fun notifyFineCreated(fine: Fine) {
        observers.forEach { it.onFineCreated(fine) }
    }
    
    private fun notifyFinePaid(fine: Fine) {
        observers.forEach { it.onFinePaid(fine) }
    }
    
    fun getCatalog(): BookCatalog = catalog
    fun getAllActiveLoans(): List<Loan> = activeLoans.values.toList()
    fun getReservations(): List<Reservation> = reservations.values.toList()
    fun getMemberFines(memberId: String): List<Fine> = fines[memberId]?.toList() ?: emptyList()
}
