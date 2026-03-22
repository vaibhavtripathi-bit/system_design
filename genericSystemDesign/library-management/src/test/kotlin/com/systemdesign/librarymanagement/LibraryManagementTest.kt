package com.systemdesign.librarymanagement

import com.systemdesign.librarymanagement.common.*
import com.systemdesign.librarymanagement.approach_01_strategy_search.*
import com.systemdesign.librarymanagement.approach_02_state_machine.*
import com.systemdesign.librarymanagement.approach_03_observer_notifications.*
import io.mockk.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.time.LocalDate
import java.time.LocalDateTime

@DisplayName("Library Management System Tests")
class LibraryManagementTest {
    
    @Nested
    @DisplayName("Search Strategy Tests")
    inner class SearchStrategyTests {
        
        private lateinit var catalog: BookCatalog
        private lateinit var library: SearchableLibrary
        
        @BeforeEach
        fun setup() {
            catalog = BookCatalog()
            catalog.addBook(Book("978-0-13-468599-1", "Clean Code", "Robert C. Martin", "Programming"))
            catalog.addBook(Book("978-0-596-51774-8", "JavaScript: The Good Parts", "Douglas Crockford", "Programming"))
            catalog.addBook(Book("978-0-201-63361-0", "Design Patterns", "Gang of Four", "Software Engineering"))
            catalog.addBook(Book("978-0-13-235088-4", "Clean Architecture", "Robert C. Martin", "Software Engineering"))
            catalog.addBook(Book("978-1-59327-584-6", "The Linux Command Line", "William Shotts", "Operating Systems"))
            
            library = SearchableLibrary(catalog)
        }
        
        @Nested
        @DisplayName("Title Search Strategy")
        inner class TitleSearchTests {
            
            @Test
            fun `exact match finds book by exact title`() {
                val strategy = TitleSearchStrategy(MatchType.EXACT)
                val results = strategy.search("Clean Code", catalog)
                
                assertEquals(1, results.size)
                assertEquals("Clean Code", results[0].title)
            }
            
            @Test
            fun `contains match finds books with partial title`() {
                val strategy = TitleSearchStrategy(MatchType.CONTAINS)
                val results = strategy.search("Clean", catalog)
                
                assertEquals(2, results.size)
                assertTrue(results.all { it.title.contains("Clean") })
            }
            
            @Test
            fun `starts with match finds books starting with query`() {
                val strategy = TitleSearchStrategy(MatchType.STARTS_WITH)
                val results = strategy.search("The", catalog)
                
                assertEquals(1, results.size)
                assertEquals("The Linux Command Line", results[0].title)
            }
            
            @Test
            fun `search is case insensitive`() {
                val strategy = TitleSearchStrategy(MatchType.CONTAINS)
                val results = strategy.search("javascript", catalog)
                
                assertEquals(1, results.size)
                assertEquals("JavaScript: The Good Parts", results[0].title)
            }
            
            @Test
            fun `fuzzy match finds similar titles`() {
                val strategy = TitleSearchStrategy(MatchType.FUZZY)
                val results = strategy.search("Cleen Code", catalog)
                
                assertTrue(results.isNotEmpty())
            }
        }
        
        @Nested
        @DisplayName("Author Search Strategy")
        inner class AuthorSearchTests {
            
            @Test
            fun `finds all books by author`() {
                val strategy = AuthorSearchStrategy(MatchType.CONTAINS)
                val results = strategy.search("Robert", catalog)
                
                assertEquals(2, results.size)
                assertTrue(results.all { it.author.contains("Robert") })
            }
            
            @Test
            fun `exact author match`() {
                val strategy = AuthorSearchStrategy(MatchType.EXACT)
                val results = strategy.search("Douglas Crockford", catalog)
                
                assertEquals(1, results.size)
                assertEquals("Douglas Crockford", results[0].author)
            }
        }
        
        @Nested
        @DisplayName("ISBN Search Strategy")
        inner class ISBNSearchTests {
            
            @Test
            fun `finds book by exact ISBN`() {
                val strategy = ISBNSearchStrategy()
                val results = strategy.search("978-0-13-468599-1", catalog)
                
                assertEquals(1, results.size)
                assertEquals("Clean Code", results[0].title)
            }
            
            @Test
            fun `finds book by ISBN without dashes`() {
                val strategy = ISBNSearchStrategy()
                val results = strategy.search("9780134685991", catalog)
                
                assertEquals(1, results.size)
                assertEquals("Clean Code", results[0].title)
            }
            
            @Test
            fun `finds book by partial ISBN`() {
                val strategy = ISBNSearchStrategy()
                val results = strategy.search("468599", catalog)
                
                assertEquals(1, results.size)
            }
        }
        
        @Nested
        @DisplayName("Genre Search Strategy")
        inner class GenreSearchTests {
            
            @Test
            fun `finds all books in genre`() {
                val strategy = GenreSearchStrategy(MatchType.EXACT)
                val results = strategy.search("Programming", catalog)
                
                assertEquals(2, results.size)
                assertTrue(results.all { it.genre == "Programming" })
            }
            
            @Test
            fun `contains search across genres`() {
                val strategy = GenreSearchStrategy(MatchType.CONTAINS)
                val results = strategy.search("Engineering", catalog)
                
                assertEquals(2, results.size)
            }
        }
        
        @Nested
        @DisplayName("Composite Search Strategy")
        inner class CompositeSearchTests {
            
            @Test
            fun `union combines results from multiple strategies`() {
                val composite = CompositeSearchStrategy(
                    listOf(
                        TitleSearchStrategy(MatchType.CONTAINS),
                        AuthorSearchStrategy(MatchType.CONTAINS)
                    ),
                    CombineMode.UNION
                )
                
                val results = composite.search("Robert", catalog)
                
                assertTrue(results.size >= 2)
            }
            
            @Test
            fun `intersection finds common results`() {
                catalog.addBook(Book("978-0-00-000000-0", "Robert's Guide", "Robert Smith", "Guide"))
                
                val composite = CompositeSearchStrategy(
                    listOf(
                        TitleSearchStrategy(MatchType.CONTAINS),
                        AuthorSearchStrategy(MatchType.CONTAINS)
                    ),
                    CombineMode.INTERSECTION
                )
                
                val results = composite.search("Robert", catalog)
                
                assertTrue(results.all { 
                    it.title.lowercase().contains("robert") && 
                    it.author.lowercase().contains("robert") 
                })
            }
        }
        
        @Nested
        @DisplayName("Multi-Field Search Strategy")
        inner class MultiFieldSearchTests {
            
            @Test
            fun `searches across all fields`() {
                val strategy = MultiFieldSearchStrategy()
                
                val titleResults = strategy.search("Clean", catalog)
                assertTrue(titleResults.isNotEmpty())
                
                val authorResults = strategy.search("Martin", catalog)
                assertTrue(authorResults.isNotEmpty())
                
                val isbnResults = strategy.search("468599", catalog)
                assertTrue(isbnResults.isNotEmpty())
            }
            
            @Test
            fun `results are sorted by relevance`() {
                val strategy = MultiFieldSearchStrategy()
                val results = strategy.search("Clean Code", catalog)
                
                assertEquals("Clean Code", results[0].title)
            }
        }
        
        @Nested
        @DisplayName("Availability Filter")
        inner class AvailabilitySearchTests {
            
            @Test
            fun `filters only available books`() {
                catalog.getBook("978-0-13-468599-1")?.status = BookStatus.BORROWED
                
                val baseStrategy = TitleSearchStrategy(MatchType.CONTAINS)
                val availabilityStrategy = AvailabilitySearchStrategy(baseStrategy)
                
                val allResults = baseStrategy.search("Clean", catalog)
                val availableResults = availabilityStrategy.search("Clean", catalog)
                
                assertEquals(2, allResults.size)
                assertEquals(1, availableResults.size)
                assertTrue(availableResults.all { it.isAvailable() })
            }
        }
        
        @Nested
        @DisplayName("Searchable Library")
        inner class SearchableLibraryTests {
            
            @Test
            fun `advanced search with multiple criteria`() {
                val results = library.advancedSearch(
                    author = "Robert",
                    genre = "Programming"
                )
                
                assertEquals(1, results.size)
                assertEquals("Clean Code", results[0].title)
            }
            
            @Test
            fun `advanced search with availability filter`() {
                catalog.getBook("978-0-13-468599-1")?.status = BookStatus.BORROWED
                
                val results = library.advancedSearch(
                    author = "Robert",
                    availableOnly = true
                )
                
                assertEquals(1, results.size)
                assertEquals("Clean Architecture", results[0].title)
            }
        }
    }
    
    @Nested
    @DisplayName("State Machine Tests")
    inner class StateMachineTests {
        
        private lateinit var library: StateMachineLibrary
        private lateinit var book: Book
        private lateinit var member: Member
        
        @BeforeEach
        fun setup() {
            library = StateMachineLibrary(
                finePolicy = FinePolicy(dailyRate = 0.50, maxFine = 25.00),
                loanPolicy = LoanPolicy(standardLoanDays = 14)
            )
            book = Book("978-0-13-468599-1", "Clean Code", "Robert C. Martin", "Programming")
            member = Member("M001", "John Doe", "john@example.com", LocalDate.now().minusYears(1))
            library.addBook(book)
        }
        
        @Nested
        @DisplayName("Book Lifecycle Transitions")
        inner class BookLifecycleTests {
            
            @Test
            fun `new book starts as available`() {
                assertEquals(BookStatus.AVAILABLE, book.status)
            }
            
            @Test
            fun `available book can be reserved`() {
                val result = library.reserve(book.isbn, member)
                
                assertTrue(result is LibraryResult.BookReserved)
                assertEquals(BookStatus.RESERVED, book.status)
            }
            
            @Test
            fun `available book can be checked out`() {
                val result = library.checkout(book.isbn, member)
                
                assertTrue(result is LibraryResult.BookCheckedOut)
                assertEquals(BookStatus.BORROWED, book.status)
            }
            
            @Test
            fun `reserved book can be checked out by reserver`() {
                library.reserve(book.isbn, member)
                val result = library.checkout(book.isbn, member)
                
                assertTrue(result is LibraryResult.BookCheckedOut)
                assertEquals(BookStatus.BORROWED, book.status)
            }
            
            @Test
            fun `reserved book cannot be checked out by different member`() {
                library.reserve(book.isbn, member)
                
                val otherMember = Member("M002", "Jane Doe", "jane@example.com", LocalDate.now())
                val result = library.checkout(book.isbn, otherMember)
                
                assertTrue(result is LibraryResult.Error)
                assertEquals(BookStatus.RESERVED, book.status)
            }
            
            @Test
            fun `borrowed book can be returned`() {
                library.checkout(book.isbn, member)
                val result = library.returnBook(book.isbn)
                
                assertTrue(result is LibraryResult.BookReturned)
                assertEquals(BookStatus.AVAILABLE, book.status)
            }
            
            @Test
            fun `borrowed book can be marked overdue`() {
                library.checkout(book.isbn, member)
                val result = library.markOverdue(book.isbn)
                
                assertTrue(result is LibraryResult.Success)
                assertEquals(BookStatus.OVERDUE, book.status)
            }
            
            @Test
            fun `overdue book can be returned`() {
                library.checkout(book.isbn, member)
                library.markOverdue(book.isbn)
                val result = library.returnBook(book.isbn)
                
                assertTrue(result is LibraryResult.BookReturned)
                assertEquals(BookStatus.AVAILABLE, book.status)
            }
            
            @Test
            fun `borrowed book can be marked lost`() {
                library.checkout(book.isbn, member)
                val result = library.markLost(book.isbn)
                
                assertTrue(result is LibraryResult.Success)
                assertEquals(BookStatus.LOST, book.status)
            }
            
            @Test
            fun `lost book can be found`() {
                library.checkout(book.isbn, member)
                library.markLost(book.isbn)
                val result = library.foundLostBook(book.isbn)
                
                assertTrue(result is LibraryResult.Success)
                assertEquals(BookStatus.AVAILABLE, book.status)
            }
            
            @Test
            fun `available book can be retired`() {
                val result = library.retire(book.isbn)
                
                assertTrue(result is LibraryResult.Success)
                assertEquals(BookStatus.RETIRED, book.status)
            }
            
            @Test
            fun `retired book cannot transition to any other state`() {
                library.retire(book.isbn)
                
                val reserveResult = library.reserve(book.isbn, member)
                assertTrue(reserveResult is LibraryResult.InvalidTransition)
                
                val checkoutResult = library.checkout(book.isbn, member)
                assertTrue(checkoutResult is LibraryResult.InvalidTransition)
            }
        }
        
        @Nested
        @DisplayName("Invalid Transitions")
        inner class InvalidTransitionTests {
            
            @Test
            fun `cannot checkout already borrowed book`() {
                library.checkout(book.isbn, member)
                
                val otherMember = Member("M002", "Jane Doe", "jane@example.com", LocalDate.now())
                val result = library.checkout(book.isbn, otherMember)
                
                assertTrue(result is LibraryResult.InvalidTransition)
            }
            
            @Test
            fun `cannot return available book`() {
                val result = library.returnBook(book.isbn)
                
                assertTrue(result is LibraryResult.InvalidTransition)
            }
            
            @Test
            fun `cannot mark available book as overdue`() {
                val result = library.markOverdue(book.isbn)
                
                assertTrue(result is LibraryResult.InvalidTransition)
            }
            
            @Test
            fun `cannot mark available book as lost`() {
                val result = library.markLost(book.isbn)
                
                assertTrue(result is LibraryResult.InvalidTransition)
            }
        }
        
        @Nested
        @DisplayName("Loan Management")
        inner class LoanManagementTests {
            
            @Test
            fun `loan has correct due date`() {
                val checkoutDate = LocalDate.of(2024, 1, 15)
                val result = library.checkout(book.isbn, member, checkoutDate)
                
                assertTrue(result is LibraryResult.BookCheckedOut)
                val loan = (result as LibraryResult.BookCheckedOut).loan
                assertEquals(LocalDate.of(2024, 1, 29), loan.dueDate)
            }
            
            @Test
            fun `member cannot exceed maximum loans`() {
                val loanPolicy = LoanPolicy(maxLoansPerMember = 2)
                val restrictedLibrary = StateMachineLibrary(loanPolicy = loanPolicy)
                
                val book1 = Book("ISBN-1", "Book 1", "Author", "Genre")
                val book2 = Book("ISBN-2", "Book 2", "Author", "Genre")
                val book3 = Book("ISBN-3", "Book 3", "Author", "Genre")
                
                restrictedLibrary.addBook(book1)
                restrictedLibrary.addBook(book2)
                restrictedLibrary.addBook(book3)
                
                restrictedLibrary.checkout(book1.isbn, member)
                restrictedLibrary.checkout(book2.isbn, member)
                val result = restrictedLibrary.checkout(book3.isbn, member)
                
                assertTrue(result is LibraryResult.Error)
                assertTrue((result as LibraryResult.Error).message.contains("maximum loans"))
            }
            
            @Test
            fun `get active loans for member`() {
                val book2 = Book("ISBN-2", "Book 2", "Author", "Genre")
                library.addBook(book2)
                
                library.checkout(book.isbn, member)
                library.checkout(book2.isbn, member)
                library.returnBook(book.isbn)
                
                val activeLoans = library.getActiveLoans(member.id)
                
                assertEquals(1, activeLoans.size)
                assertEquals(book2.isbn, activeLoans[0].book.isbn)
            }
        }
        
        @Nested
        @DisplayName("Fine Calculation")
        inner class FineCalculationTests {
            
            @Test
            fun `no fine for on-time return`() {
                val checkoutDate = LocalDate.of(2024, 1, 1)
                val returnDate = LocalDate.of(2024, 1, 10)
                
                library.checkout(book.isbn, member, checkoutDate)
                val result = library.returnBook(book.isbn, returnDate)
                
                assertTrue(result is LibraryResult.BookReturned)
                assertNull((result as LibraryResult.BookReturned).fine)
            }
            
            @Test
            fun `fine calculated for overdue return`() {
                val checkoutDate = LocalDate.of(2024, 1, 1)
                val returnDate = LocalDate.of(2024, 1, 20)
                
                library.checkout(book.isbn, member, checkoutDate)
                val result = library.returnBook(book.isbn, returnDate)
                
                assertTrue(result is LibraryResult.BookReturned)
                val fine = (result as LibraryResult.BookReturned).fine
                assertNotNull(fine)
                assertEquals(2.50, fine!!.amount)
            }
            
            @Test
            fun `fine capped at maximum`() {
                val checkoutDate = LocalDate.of(2024, 1, 1)
                // Due date is Jan 15 (14 days), return date Apr 1 gives >50 days overdue to hit the $25 cap
                val returnDate = LocalDate.of(2024, 4, 1)
                
                library.checkout(book.isbn, member, checkoutDate)
                val result = library.returnBook(book.isbn, returnDate)
                
                assertTrue(result is LibraryResult.BookReturned)
                val fine = (result as LibraryResult.BookReturned).fine
                assertNotNull(fine)
                assertEquals(25.00, fine!!.amount) // Capped at $25 max
            }
            
            @Test
            fun `lost book incurs double max fine`() {
                library.checkout(book.isbn, member)
                library.markLost(book.isbn)
                
                val unpaidFines = library.getUnpaidFines(member.id)
                
                assertEquals(1, unpaidFines.size)
                assertEquals(50.00, unpaidFines[0].amount)
            }
            
            @Test
            fun `fine policy with grace period`() {
                val policyWithGrace = FinePolicy(dailyRate = 1.00, gracePeriodDays = 3)
                
                assertEquals(0.0, policyWithGrace.calculateFine(2))
                assertEquals(0.0, policyWithGrace.calculateFine(3))
                assertEquals(2.0, policyWithGrace.calculateFine(5))
            }
            
            @Test
            fun `member with unpaid fines cannot checkout`() {
                val checkoutDate = LocalDate.of(2024, 1, 1)
                val returnDate = LocalDate.of(2024, 1, 20)
                
                library.checkout(book.isbn, member, checkoutDate)
                library.returnBook(book.isbn, returnDate)
                
                val book2 = Book("ISBN-2", "Book 2", "Author", "Genre")
                library.addBook(book2)
                
                val result = library.checkout(book2.isbn, member)
                
                assertTrue(result is LibraryResult.Error)
                assertTrue((result as LibraryResult.Error).message.contains("unpaid fines"))
            }
            
            @Test
            fun `pay fine allows checkout`() {
                val checkoutDate = LocalDate.of(2024, 1, 1)
                val returnDate = LocalDate.of(2024, 1, 20)
                
                library.checkout(book.isbn, member, checkoutDate)
                val returnResult = library.returnBook(book.isbn, returnDate)
                val fine = (returnResult as LibraryResult.BookReturned).fine!!
                
                library.payFine(fine.loan.id, member.id)
                
                val book2 = Book("ISBN-2", "Book 2", "Author", "Genre")
                library.addBook(book2)
                
                val result = library.checkout(book2.isbn, member)
                
                assertTrue(result is LibraryResult.BookCheckedOut)
            }
        }
        
        @Nested
        @DisplayName("Reservation Handling")
        inner class ReservationHandlingTests {
            
            @Test
            fun `reservation expires after configured days`() {
                val reservationTime = LocalDateTime.of(2024, 1, 1, 10, 0)
                library.reserve(book.isbn, member, reservationTime)
                
                val afterExpiry = LocalDateTime.of(2024, 1, 5, 10, 0)
                val expired = library.expireReservations(afterExpiry)
                
                assertEquals(1, expired.size)
                assertEquals(BookStatus.AVAILABLE, book.status)
            }
            
            @Test
            fun `cancel reservation makes book available`() {
                val result = library.reserve(book.isbn, member)
                val reservation = (result as LibraryResult.BookReserved).reservation
                
                library.cancelReservation(reservation.id)
                
                assertEquals(BookStatus.AVAILABLE, book.status)
            }
            
            @Test
            fun `reservation for borrowed book adds to waitlist`() {
                library.checkout(book.isbn, member)
                
                val waitingMember = Member("M002", "Jane Doe", "jane@example.com", LocalDate.now())
                val result = library.reserve(book.isbn, waitingMember)
                
                assertTrue(result is LibraryResult.Success)
                assertTrue((result as LibraryResult.Success).message.contains("waitlist"))
                
                val waitlist = library.getWaitlist(book.isbn)
                assertEquals(1, waitlist.size)
                assertEquals(waitingMember.id, waitlist[0].id)
            }
            
            @Test
            fun `next in waitlist notified when book returned`() {
                library.checkout(book.isbn, member)
                
                val waitingMember = Member("M002", "Jane Doe", "jane@example.com", LocalDate.now())
                library.reserve(book.isbn, waitingMember)
                
                library.returnBook(book.isbn)
                
                val nextInWaitlist = library.getNextInWaitlist(book.isbn)
                assertNull(nextInWaitlist)
            }
        }
        
        @Nested
        @DisplayName("Overdue Detection")
        inner class OverdueDetectionTests {
            
            @Test
            fun `automatically detect overdue books`() {
                val checkoutDate = LocalDate.of(2024, 1, 1)
                library.checkout(book.isbn, member, checkoutDate)
                
                val currentDate = LocalDate.of(2024, 1, 20)
                val overdueLoans = library.checkAndUpdateOverdueBooks(currentDate)
                
                assertEquals(1, overdueLoans.size)
                assertEquals(BookStatus.OVERDUE, book.status)
            }
            
            @Test
            fun `on-time loans not marked overdue`() {
                val checkoutDate = LocalDate.of(2024, 1, 1)
                library.checkout(book.isbn, member, checkoutDate)
                
                val currentDate = LocalDate.of(2024, 1, 10)
                val overdueLoans = library.checkAndUpdateOverdueBooks(currentDate)
                
                assertEquals(0, overdueLoans.size)
                assertEquals(BookStatus.BORROWED, book.status)
            }
        }
    }
    
    @Nested
    @DisplayName("Observer Pattern Tests")
    inner class ObserverPatternTests {
        
        private lateinit var library: ObservableLibrary
        private lateinit var book: Book
        private lateinit var member: Member
        
        @BeforeEach
        fun setup() {
            library = ObservableLibrary(
                finePolicy = FinePolicy(dailyRate = 0.50),
                loanPolicy = LoanPolicy(standardLoanDays = 14, reservationHoldDays = 3)
            )
            book = Book("978-0-13-468599-1", "Clean Code", "Robert C. Martin", "Programming")
            member = Member("M001", "John Doe", "john@example.com", LocalDate.now().minusYears(1))
            library.addBook(book)
        }
        
        @Nested
        @DisplayName("Email Notifier")
        inner class EmailNotifierTests {
            
            private lateinit var emailService: ConsoleEmailService
            private lateinit var emailNotifier: EmailNotifier
            
            @BeforeEach
            fun setupNotifier() {
                emailService = ConsoleEmailService()
                emailNotifier = EmailNotifier(emailService)
                library.addObserver(emailNotifier)
            }
            
            @Test
            fun `sends email on book reservation`() {
                library.reserve(book.isbn, member)
                
                val sentEmails = emailService.getSentEmails()
                assertEquals(1, sentEmails.size)
                assertEquals(member.email, sentEmails[0].first)
                assertTrue(sentEmails[0].second.contains("Reserved"))
            }
            
            @Test
            fun `sends email on book checkout`() {
                library.checkout(book.isbn, member)
                
                val sentEmails = emailService.getSentEmails()
                assertEquals(1, sentEmails.size)
                assertTrue(sentEmails[0].second.contains("Checked Out"))
            }
            
            @Test
            fun `sends email for due date reminder`() {
                val checkoutDate = LocalDate.of(2024, 1, 1)
                library.checkout(book.isbn, member, checkoutDate)
                emailService.clearSentEmails()
                
                val currentDate = LocalDate.of(2024, 1, 13)
                library.checkDueDates(currentDate, reminderDays = 3)
                
                val sentEmails = emailService.getSentEmails()
                assertEquals(1, sentEmails.size)
                assertTrue(sentEmails[0].second.contains("Due Soon"))
            }
            
            @Test
            fun `sends email for overdue book`() {
                val checkoutDate = LocalDate.of(2024, 1, 1)
                library.checkout(book.isbn, member, checkoutDate)
                emailService.clearSentEmails()
                
                val currentDate = LocalDate.of(2024, 1, 20)
                library.checkDueDates(currentDate)
                
                val sentEmails = emailService.getSentEmails()
                assertEquals(1, sentEmails.size)
                assertTrue(sentEmails[0].second.contains("OVERDUE"))
            }
            
            @Test
            fun `sends email when book becomes available`() {
                library.checkout(book.isbn, member)
                
                val waitingMember = Member("M002", "Jane Doe", "jane@example.com", LocalDate.now())
                library.reserve(book.isbn, waitingMember)
                emailService.clearSentEmails()
                
                library.returnBook(book.isbn)
                
                val sentEmails = emailService.getSentEmails()
                assertTrue(sentEmails.any { it.second.contains("Available") })
            }
            
            @Test
            fun `sends email on fine creation`() {
                val checkoutDate = LocalDate.of(2024, 1, 1)
                library.checkout(book.isbn, member, checkoutDate)
                emailService.clearSentEmails()
                
                val returnDate = LocalDate.of(2024, 1, 20)
                library.returnBook(book.isbn, returnDate)
                
                val sentEmails = emailService.getSentEmails()
                assertTrue(sentEmails.any { it.second.contains("Fine") })
            }
        }
        
        @Nested
        @DisplayName("System Notifier")
        inner class SystemNotifierTests {
            
            private lateinit var systemNotifier: SystemNotifier
            
            @BeforeEach
            fun setupNotifier() {
                systemNotifier = SystemNotifier()
                library.addObserver(systemNotifier)
            }
            
            @Test
            fun `creates in-app notification on checkout`() {
                library.checkout(book.isbn, member)
                
                val notifications = systemNotifier.getNotifications(member.id)
                assertEquals(1, notifications.size)
                assertEquals(SystemNotifier.NotificationType.CHECKOUT, notifications[0].type)
            }
            
            @Test
            fun `notifications start as unread`() {
                library.checkout(book.isbn, member)
                
                val unread = systemNotifier.getUnreadNotifications(member.id)
                assertEquals(1, unread.size)
                assertFalse(unread[0].read)
            }
            
            @Test
            fun `can mark notification as read`() {
                library.checkout(book.isbn, member)
                
                val notification = systemNotifier.getNotifications(member.id)[0]
                systemNotifier.markAsRead(notification.id, member.id)
                
                val unread = systemNotifier.getUnreadNotifications(member.id)
                assertEquals(0, unread.size)
            }
            
            @Test
            fun `can mark all notifications as read`() {
                library.checkout(book.isbn, member)
                library.returnBook(book.isbn)
                
                systemNotifier.markAllAsRead(member.id)
                
                val unread = systemNotifier.getUnreadNotifications(member.id)
                assertEquals(0, unread.size)
            }
        }
        
        @Nested
        @DisplayName("Overdue Reporter")
        inner class OverdueReporterTests {
            
            private lateinit var overdueReporter: OverdueReporter
            
            @BeforeEach
            fun setupReporter() {
                overdueReporter = OverdueReporter()
                library.addObserver(overdueReporter)
            }
            
            @Test
            fun `tracks overdue books`() {
                val checkoutDate = LocalDate.of(2024, 1, 1)
                library.checkout(book.isbn, member, checkoutDate)
                
                val currentDate = LocalDate.of(2024, 1, 20)
                library.checkDueDates(currentDate)
                
                val report = overdueReporter.getOverdueReport()
                assertEquals(1, report.size)
                assertEquals(5, report[0].daysOverdue)
            }
            
            @Test
            fun `calculates estimated fines`() {
                val checkoutDate = LocalDate.of(2024, 1, 1)
                library.checkout(book.isbn, member, checkoutDate)
                
                val currentDate = LocalDate.of(2024, 1, 20)
                library.checkDueDates(currentDate)
                
                val totalFines = overdueReporter.getTotalEstimatedFines()
                assertEquals(2.5, totalFines)
            }
            
            @Test
            fun `removes from report when returned`() {
                val checkoutDate = LocalDate.of(2024, 1, 1)
                library.checkout(book.isbn, member, checkoutDate)
                
                library.checkDueDates(LocalDate.of(2024, 1, 20))
                assertEquals(1, overdueReporter.getOverdueCount())
                
                library.returnBook(book.isbn, LocalDate.of(2024, 1, 21))
                assertEquals(0, overdueReporter.getOverdueCount())
            }
            
            @Test
            fun `get overdue by member`() {
                val book2 = Book("ISBN-2", "Book 2", "Author", "Genre")
                library.addBook(book2)
                
                val member2 = Member("M002", "Jane", "jane@example.com", LocalDate.now())
                
                val checkoutDate = LocalDate.of(2024, 1, 1)
                library.checkout(book.isbn, member, checkoutDate)
                library.checkout(book2.isbn, member2, checkoutDate)
                
                library.checkDueDates(LocalDate.of(2024, 1, 20))
                
                val member1Overdue = overdueReporter.getOverdueByMember(member.id)
                assertEquals(1, member1Overdue.size)
            }
        }
        
        @Nested
        @DisplayName("Audit Logger")
        inner class AuditLoggerTests {
            
            private lateinit var auditLogger: AuditLogger
            
            @BeforeEach
            fun setupLogger() {
                auditLogger = AuditLogger()
                library.addObserver(auditLogger)
            }
            
            @Test
            fun `logs all events`() {
                library.reserve(book.isbn, member)
                library.checkout(book.isbn, member)
                library.returnBook(book.isbn)
                
                val log = auditLogger.getAuditLog()
                assertTrue(log.size >= 3)
            }
            
            @Test
            fun `filter log by event type`() {
                library.checkout(book.isbn, member)
                library.returnBook(book.isbn)
                
                val checkoutLogs = auditLogger.getAuditLogByEventType("BOOK_CHECKED_OUT")
                assertEquals(1, checkoutLogs.size)
            }
            
            @Test
            fun `filter log by member`() {
                val member2 = Member("M002", "Jane", "jane@example.com", LocalDate.now())
                val book2 = Book("ISBN-2", "Book 2", "Author", "Genre")
                library.addBook(book2)
                
                library.checkout(book.isbn, member)
                library.checkout(book2.isbn, member2)
                
                val member1Logs = auditLogger.getAuditLogForMember(member.id)
                assertEquals(1, member1Logs.size)
            }
            
            @Test
            fun `filter log by date range`() {
                library.checkout(book.isbn, member)
                
                val from = LocalDateTime.now().minusMinutes(1)
                val to = LocalDateTime.now().plusMinutes(1)
                
                val logsInRange = auditLogger.getAuditLogInRange(from, to)
                assertEquals(1, logsInRange.size)
            }
        }
        
        @Nested
        @DisplayName("Multiple Observers")
        inner class MultipleObserversTests {
            
            @Test
            fun `all observers receive events`() {
                val emailService = ConsoleEmailService()
                val emailNotifier = EmailNotifier(emailService)
                val systemNotifier = SystemNotifier()
                val auditLogger = AuditLogger()
                
                library.addObserver(emailNotifier)
                library.addObserver(systemNotifier)
                library.addObserver(auditLogger)
                
                library.checkout(book.isbn, member)
                
                assertEquals(1, emailService.getSentEmails().size)
                assertEquals(1, systemNotifier.getNotifications(member.id).size)
                assertEquals(1, auditLogger.getAuditLog().size)
            }
            
            @Test
            fun `can remove observers`() {
                val emailService = ConsoleEmailService()
                val emailNotifier = EmailNotifier(emailService)
                
                library.addObserver(emailNotifier)
                library.removeObserver(emailNotifier)
                
                library.checkout(book.isbn, member)
                
                assertEquals(0, emailService.getSentEmails().size)
            }
        }
        
        @Nested
        @DisplayName("Waitlist Notifications")
        inner class WaitlistNotificationTests {
            
            @Test
            fun `notifies next in waitlist when book available`() {
                val systemNotifier = SystemNotifier()
                library.addObserver(systemNotifier)
                
                library.checkout(book.isbn, member)
                
                val waitingMember = Member("M002", "Jane", "jane@example.com", LocalDate.now())
                library.reserve(book.isbn, waitingMember)
                
                library.returnBook(book.isbn)
                
                val notifications = systemNotifier.getNotifications(waitingMember.id)
                assertTrue(notifications.any { it.type == SystemNotifier.NotificationType.AVAILABLE })
            }
        }
    }
    
    @Nested
    @DisplayName("Integration Tests")
    inner class IntegrationTests {
        
        @Test
        fun `complete book lifecycle with notifications`() {
            val library = ObservableLibrary()
            val systemNotifier = SystemNotifier()
            library.addObserver(systemNotifier)
            
            val book = Book("ISBN-001", "Test Book", "Test Author", "Test Genre")
            library.addBook(book)
            
            val member = Member("M001", "John", "john@test.com", LocalDate.now())
            
            library.reserve(book.isbn, member)
            assertEquals(BookStatus.RESERVED, book.status)
            
            library.checkout(book.isbn, member)
            assertEquals(BookStatus.BORROWED, book.status)
            
            library.returnBook(book.isbn)
            assertEquals(BookStatus.AVAILABLE, book.status)
            
            val notifications = systemNotifier.getNotifications(member.id)
            assertEquals(3, notifications.size)
        }
        
        @Test
        fun `search and checkout workflow`() {
            val searchLibrary = SearchableLibrary()
            val stateLibrary = StateMachineLibrary(catalog = searchLibrary.getCatalog())
            
            val book1 = Book("ISBN-001", "Clean Code", "Robert Martin", "Programming")
            val book2 = Book("ISBN-002", "Clean Architecture", "Robert Martin", "Programming")
            val book3 = Book("ISBN-003", "The Pragmatic Programmer", "Hunt & Thomas", "Programming")
            
            searchLibrary.addBook(book1)
            searchLibrary.addBook(book2)
            searchLibrary.addBook(book3)
            
            val results = searchLibrary.searchWith("Robert", AuthorSearchStrategy())
            assertEquals(2, results.size)
            
            val member = Member("M001", "John", "john@test.com", LocalDate.now())
            val checkoutResult = stateLibrary.checkout(results[0].isbn, member)
            
            assertTrue(checkoutResult is LibraryResult.BookCheckedOut)
            
            val availableResults = searchLibrary.searchWith("Robert", 
                AvailabilitySearchStrategy(AuthorSearchStrategy()))
            assertEquals(1, availableResults.size)
        }
    }
    
    @Nested
    @DisplayName("Model Tests")
    inner class ModelTests {
        
        @Test
        fun `loan correctly detects overdue status`() {
            val book = Book("ISBN-001", "Test", "Author", "Genre")
            val member = Member("M001", "John", "john@test.com", LocalDate.now())
            
            val loan = Loan(
                id = "L001",
                book = book,
                member = member,
                checkoutDate = LocalDate.of(2024, 1, 1),
                dueDate = LocalDate.of(2024, 1, 15)
            )
            
            assertFalse(loan.isOverdue(LocalDate.of(2024, 1, 10)))
            assertFalse(loan.isOverdue(LocalDate.of(2024, 1, 15)))
            assertTrue(loan.isOverdue(LocalDate.of(2024, 1, 16)))
        }
        
        @Test
        fun `loan calculates days overdue correctly`() {
            val book = Book("ISBN-001", "Test", "Author", "Genre")
            val member = Member("M001", "John", "john@test.com", LocalDate.now())
            
            val loan = Loan(
                id = "L001",
                book = book,
                member = member,
                checkoutDate = LocalDate.of(2024, 1, 1),
                dueDate = LocalDate.of(2024, 1, 15)
            )
            
            assertEquals(0, loan.getDaysOverdue(LocalDate.of(2024, 1, 10)))
            assertEquals(0, loan.getDaysOverdue(LocalDate.of(2024, 1, 15)))
            assertEquals(5, loan.getDaysOverdue(LocalDate.of(2024, 1, 20)))
        }
        
        @Test
        fun `reservation correctly detects expiration`() {
            val book = Book("ISBN-001", "Test", "Author", "Genre")
            val member = Member("M001", "John", "john@test.com", LocalDate.now())
            
            val reservation = Reservation(
                id = "R001",
                book = book,
                member = member,
                reservedAt = LocalDateTime.of(2024, 1, 1, 10, 0),
                expiresAt = LocalDateTime.of(2024, 1, 4, 10, 0)
            )
            
            assertTrue(reservation.isActive(LocalDateTime.of(2024, 1, 2, 10, 0)))
            assertFalse(reservation.isActive(LocalDateTime.of(2024, 1, 5, 10, 0)))
        }
        
        @Test
        fun `book catalog operations`() {
            val catalog = BookCatalog()
            
            val book1 = Book("ISBN-001", "Book 1", "Author 1", "Genre 1")
            val book2 = Book("ISBN-002", "Book 2", "Author 2", "Genre 2", BookStatus.BORROWED)
            
            catalog.addBook(book1)
            catalog.addBook(book2)
            
            assertEquals(2, catalog.size())
            assertEquals(1, catalog.getAvailableBooks().size)
            assertEquals(book1, catalog.getBook("ISBN-001"))
            
            val removed = catalog.removeBook("ISBN-001")
            assertEquals(book1, removed)
            assertEquals(1, catalog.size())
        }
        
        @Test
        fun `member membership duration`() {
            val member = Member(
                "M001", 
                "John", 
                "john@test.com", 
                LocalDate.now().minusDays(100)
            )
            
            assertEquals(100, member.getMembershipDurationDays())
        }
    }
}
