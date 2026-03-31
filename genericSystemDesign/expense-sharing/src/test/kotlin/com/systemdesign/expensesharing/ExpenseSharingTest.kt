package com.systemdesign.expensesharing

import com.systemdesign.expensesharing.common.*
import com.systemdesign.expensesharing.approach_01_graph_simplification.*
import com.systemdesign.expensesharing.approach_02_mediator_settlement.*
import com.systemdesign.expensesharing.approach_03_event_sourcing.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.time.LocalDateTime
import kotlin.math.abs

@DisplayName("Expense Sharing Tests")
class ExpenseSharingTest {

    companion object {
        val alice = User(id = "alice", name = "Alice", email = "alice@test.com")
        val bob = User(id = "bob", name = "Bob", email = "bob@test.com")
        val charlie = User(id = "charlie", name = "Charlie", email = "charlie@test.com")
        val diana = User(id = "diana", name = "Diana", email = "diana@test.com")

        fun assertAmountEquals(expected: Double, actual: Double, message: String = "") {
            assertTrue(abs(expected - actual) < 0.02, "$message Expected: $expected, Actual: $actual")
        }
    }

    @Nested
    @DisplayName("Graph Simplification Approach")
    inner class GraphSimplificationTests {

        @Nested
        @DisplayName("Split Calculators")
        inner class SplitCalculatorTests {

            @Test
            fun `equal split among two users`() {
                val calc = EqualSplitCalculator()
                val result = calc.calculate(100.0, listOf(alice, bob))

                assertAmountEquals(50.0, result[alice]!!)
                assertAmountEquals(50.0, result[bob]!!)
            }

            @Test
            fun `equal split handles remainder for three users`() {
                val calc = EqualSplitCalculator()
                val result = calc.calculate(100.0, listOf(alice, bob, charlie))

                val total = result.values.sum()
                assertAmountEquals(100.0, total)
                result.values.forEach { assertTrue(it >= 33.33 && it <= 33.34) }
            }

            @Test
            fun `equal split single participant gets full amount`() {
                val calc = EqualSplitCalculator()
                val result = calc.calculate(75.0, listOf(alice))

                assertAmountEquals(75.0, result[alice]!!)
            }

            @Test
            fun `equal split empty participants throws`() {
                val calc = EqualSplitCalculator()
                assertThrows<IllegalArgumentException> { calc.calculate(100.0, emptyList()) }
            }

            @Test
            fun `exact split validates total`() {
                val calc = ExactSplitCalculator()
                val result = calc.calculate(100.0, listOf(alice, bob), mapOf(alice to 70.0, bob to 30.0))

                assertAmountEquals(70.0, result[alice]!!)
                assertAmountEquals(30.0, result[bob]!!)
            }

            @Test
            fun `exact split rejects mismatched total`() {
                val calc = ExactSplitCalculator()
                assertThrows<IllegalArgumentException> {
                    calc.calculate(100.0, listOf(alice, bob), mapOf(alice to 60.0, bob to 30.0))
                }
            }

            @Test
            fun `percentage split computes correctly`() {
                val calc = PercentageSplitCalculator()
                val result = calc.calculate(200.0, listOf(alice, bob), mapOf(alice to 60.0, bob to 40.0))

                assertAmountEquals(120.0, result[alice]!!)
                assertAmountEquals(80.0, result[bob]!!)
            }

            @Test
            fun `percentage split rejects non-100 total`() {
                val calc = PercentageSplitCalculator()
                assertThrows<IllegalArgumentException> {
                    calc.calculate(100.0, listOf(alice, bob), mapOf(alice to 60.0, bob to 30.0))
                }
            }

            @Test
            fun `shares split distributes proportionally`() {
                val calc = SharesSplitCalculator()
                val result = calc.calculate(90.0, listOf(alice, bob, charlie),
                    mapOf(alice to 3.0, bob to 2.0, charlie to 1.0))

                assertAmountEquals(45.0, result[alice]!!)
                assertAmountEquals(30.0, result[bob]!!)
                assertAmountEquals(15.0, result[charlie]!!)
            }

            @Test
            fun `shares split rejects zero shares`() {
                val calc = SharesSplitCalculator()
                assertThrows<IllegalArgumentException> {
                    calc.calculate(100.0, listOf(alice, bob), mapOf(alice to 1.0, bob to 0.0))
                }
            }
        }

        @Nested
        @DisplayName("Debt Graph Operations")
        inner class DebtGraphTests {

            private lateinit var graph: DebtGraph

            @BeforeEach
            fun setup() { graph = DebtGraph() }

            @Test
            fun `add single edge`() {
                graph.addEdge(alice, bob, 50.0)
                val edges = graph.getEdges()

                assertEquals(1, edges.size)
                assertAmountEquals(50.0, edges[0].amount)
            }

            @Test
            fun `opposite edges cancel out`() {
                graph.addEdge(alice, bob, 50.0)
                graph.addEdge(bob, alice, 50.0)

                assertTrue(graph.getEdges().isEmpty())
            }

            @Test
            fun `opposite edges net correctly`() {
                graph.addEdge(alice, bob, 50.0)
                graph.addEdge(bob, alice, 30.0)

                val edges = graph.getEdges()
                assertEquals(1, edges.size)
                assertEquals(alice, edges[0].from)
                assertAmountEquals(20.0, edges[0].amount)
            }

            @Test
            fun `remove edge reduces debt`() {
                graph.addEdge(alice, bob, 50.0)
                graph.removeEdge(alice, bob, 20.0)

                assertAmountEquals(30.0, graph.getDebtFrom(alice, bob))
            }

            @Test
            fun `remove edge fully clears debt`() {
                graph.addEdge(alice, bob, 50.0)
                graph.removeEdge(alice, bob, 50.0)

                assertTrue(graph.getEdges().isEmpty())
            }

            @Test
            fun `net balances sum to zero`() {
                graph.addEdge(alice, bob, 30.0)
                graph.addEdge(bob, charlie, 20.0)
                graph.addEdge(charlie, alice, 10.0)

                val balances = graph.getNetBalances()
                val total = balances.values.sum()
                assertAmountEquals(0.0, total)
            }

            @Test
            fun `simplify three-person cycle`() {
                graph.addEdge(alice, bob, 30.0)
                graph.addEdge(bob, charlie, 20.0)
                graph.addEdge(charlie, alice, 10.0)

                val settlements = graph.simplify()
                assertTrue(settlements.size <= 2)

                val totalIn = settlements.groupBy { it.to }.mapValues { it.value.sumOf { s -> s.amount } }
                val totalOut = settlements.groupBy { it.from }.mapValues { it.value.sumOf { s -> s.amount } }
                val net = mutableMapOf<User, Double>()
                totalIn.forEach { (u, a) -> net[u] = (net[u] ?: 0.0) + a }
                totalOut.forEach { (u, a) -> net[u] = (net[u] ?: 0.0) - a }
            }

            @Test
            fun `simplify already balanced graph returns empty`() {
                graph.addEdge(alice, bob, 50.0)
                graph.addEdge(bob, alice, 50.0)

                val settlements = graph.simplify()
                assertTrue(settlements.isEmpty())
            }

            @Test
            fun `self-edge is ignored`() {
                graph.addEdge(alice, alice, 100.0)
                assertTrue(graph.getEdges().isEmpty())
            }

            @Test
            fun `zero amount edge is ignored`() {
                graph.addEdge(alice, bob, 0.0)
                assertTrue(graph.getEdges().isEmpty())
            }
        }

        @Nested
        @DisplayName("Full Sharing Integration")
        inner class GraphSharingIntegrationTests {

            private lateinit var sharing: GraphSimplificationSharing

            @BeforeEach
            fun setup() { sharing = GraphSimplificationSharing() }

            @Test
            fun `equal expense creates correct debts`() {
                val result = sharing.addExpenseEqual("Dinner", 90.0, alice, listOf(alice, bob, charlie))

                assertTrue(result is SharingResult.Success)
                assertAmountEquals(30.0, sharing.getDebtsOf(bob)[alice] ?: 0.0)
                assertAmountEquals(30.0, sharing.getDebtsOf(charlie)[alice] ?: 0.0)
            }

            @Test
            fun `payer is not in their own debt list`() {
                sharing.addExpenseEqual("Lunch", 60.0, alice, listOf(alice, bob))

                assertTrue(sharing.getDebtsOf(alice).isEmpty())
                assertAmountEquals(30.0, sharing.getCreditsOf(alice)[bob] ?: 0.0)
            }

            @Test
            fun `percentage expense creates correct debts`() {
                sharing.addExpensePercentage("Trip", 200.0, alice,
                    mapOf(alice to 50.0, bob to 30.0, charlie to 20.0))

                assertAmountEquals(60.0, sharing.getDebtsOf(bob)[alice] ?: 0.0)
                assertAmountEquals(40.0, sharing.getDebtsOf(charlie)[alice] ?: 0.0)
            }

            @Test
            fun `exact expense creates correct debts`() {
                sharing.addExpenseExact("Supplies", 100.0, alice,
                    mapOf(alice to 40.0, bob to 35.0, charlie to 25.0))

                assertAmountEquals(35.0, sharing.getDebtsOf(bob)[alice] ?: 0.0)
                assertAmountEquals(25.0, sharing.getDebtsOf(charlie)[alice] ?: 0.0)
            }

            @Test
            fun `shares expense distributes proportionally`() {
                sharing.addExpenseByShares("Groceries", 120.0, alice,
                    mapOf(alice to 2.0, bob to 1.0))

                assertAmountEquals(40.0, sharing.getDebtsOf(bob)[alice] ?: 0.0)
            }

            @Test
            fun `payment reduces debt`() {
                sharing.addExpenseEqual("Dinner", 100.0, alice, listOf(alice, bob))
                assertTrue(sharing.recordPayment(bob, alice, 20.0))

                assertAmountEquals(30.0, sharing.getDebtsOf(bob)[alice] ?: 0.0)
            }

            @Test
            fun `full payment clears debt`() {
                sharing.addExpenseEqual("Coffee", 20.0, alice, listOf(alice, bob))
                sharing.recordPayment(bob, alice, 10.0)

                assertTrue(sharing.getDebtsOf(bob).isEmpty())
            }

            @Test
            fun `payment fails when no debt exists`() {
                assertFalse(sharing.recordPayment(bob, alice, 50.0))
            }

            @Test
            fun `simplify debts minimizes transactions`() {
                sharing.addExpenseEqual("Dinner", 90.0, alice, listOf(alice, bob, charlie))
                sharing.addExpenseEqual("Movie", 60.0, bob, listOf(alice, bob, charlie))

                val result = sharing.simplifyDebts()
                assertTrue(result is SettlementResult.Success)
                val settlements = (result as SettlementResult.Success).settlements
                assertTrue(settlements.size <= 2)
            }

            @Test
            fun `no settlements when balanced`() {
                val result = sharing.simplifyDebts()
                assertTrue(result is SettlementResult.NoSettlementsNeeded)
            }

            @Test
            fun `circular debts cancel out`() {
                sharing.addExpenseEqual("A pays", 60.0, alice, listOf(alice, bob, charlie))
                sharing.addExpenseEqual("B pays", 60.0, bob, listOf(alice, bob, charlie))
                sharing.addExpenseEqual("C pays", 60.0, charlie, listOf(alice, bob, charlie))

                assertAmountEquals(0.0, sharing.getNetBalance(alice))
                assertAmountEquals(0.0, sharing.getNetBalance(bob))
                assertAmountEquals(0.0, sharing.getNetBalance(charlie))
            }

            @Test
            fun `group expenses tracked separately`() {
                val group = sharing.createGroup("Trip", listOf(alice, bob))
                sharing.addExpenseEqual("Hotel", 200.0, alice, listOf(alice, bob), groupId = group.id)
                sharing.addExpenseEqual("Unrelated", 50.0, alice, listOf(alice, charlie))

                assertEquals(1, sharing.getExpensesByGroup(group.id).size)
                assertEquals(2, sharing.getExpenses().size)
            }

            @Test
            fun `validation rejects negative amount`() {
                val result = sharing.addExpenseEqual("Bad", -10.0, alice, listOf(alice, bob))
                assertTrue(result is SharingResult.ValidationError)
            }

            @Test
            fun `validation rejects empty participants`() {
                val result = sharing.addExpenseEqual("Bad", 100.0, alice, emptyList())
                assertTrue(result is SharingResult.ValidationError)
            }

            @Test
            fun `net balance reflects all transactions`() {
                sharing.addExpenseEqual("Dinner", 100.0, alice, listOf(alice, bob))
                assertAmountEquals(50.0, sharing.getNetBalance(alice))
                assertAmountEquals(-50.0, sharing.getNetBalance(bob))
            }

            @Test
            fun `four person settlement is optimal`() {
                sharing.addExpenseEqual("Hotel", 400.0, alice, listOf(alice, bob, charlie, diana))
                sharing.addExpenseEqual("Gas", 80.0, bob, listOf(alice, bob, charlie, diana))
                sharing.addExpenseEqual("Food", 120.0, charlie, listOf(alice, bob, charlie, diana))

                val result = sharing.simplifyDebts()
                assertTrue(result is SettlementResult.Success)
                val settlements = (result as SettlementResult.Success).settlements
                assertTrue(settlements.size <= 3)

                val totalPaid = settlements.sumOf { it.amount }
                assertTrue(totalPaid > 0)
            }

            @Test
            fun `expenses by user returns relevant expenses`() {
                sharing.addExpenseEqual("Alice paid", 50.0, alice, listOf(alice, bob))
                sharing.addExpenseEqual("Bob paid", 30.0, bob, listOf(bob, charlie))

                assertEquals(1, sharing.getExpensesByUser(alice).filter { it.paidBy == alice }.size)
                assertEquals(2, sharing.getExpensesByUser(bob).size)
            }

            @Test
            fun `total expenses sums correctly`() {
                sharing.addExpenseEqual("A", 50.0, alice, listOf(alice, bob))
                sharing.addExpenseEqual("B", 30.0, bob, listOf(alice, bob))

                assertAmountEquals(80.0, sharing.getTotalExpenses())
            }
        }
    }

    @Nested
    @DisplayName("Mediator Settlement Approach")
    inner class MediatorSettlementTests {

        @Nested
        @DisplayName("Core Mediator Operations")
        inner class CoreMediatorTests {

            private lateinit var mediator: SettlementMediator

            @BeforeEach
            fun setup() { mediator = SettlementMediator() }

            @Test
            fun `equal expense creates correct debts`() {
                mediator.addExpenseEqual("Dinner", 90.0, alice, listOf(alice, bob, charlie))

                assertAmountEquals(30.0, mediator.getDebtsOf(bob)[alice] ?: 0.0)
                assertAmountEquals(30.0, mediator.getDebtsOf(charlie)[alice] ?: 0.0)
            }

            @Test
            fun `percentage expense distributes correctly`() {
                mediator.addExpensePercentage("Party", 200.0, alice,
                    mapOf(alice to 40.0, bob to 35.0, charlie to 25.0))

                assertAmountEquals(70.0, mediator.getDebtsOf(bob)[alice] ?: 0.0)
                assertAmountEquals(50.0, mediator.getDebtsOf(charlie)[alice] ?: 0.0)
            }

            @Test
            fun `exact expense validates and records`() {
                val result = mediator.addExpenseExact("Supplies", 100.0, alice,
                    mapOf(alice to 50.0, bob to 30.0, charlie to 20.0))

                assertTrue(result is SharingResult.Success)
                assertAmountEquals(30.0, mediator.getDebtsOf(bob)[alice] ?: 0.0)
            }

            @Test
            fun `exact expense rejects mismatched total`() {
                val result = mediator.addExpenseExact("Bad", 100.0, alice,
                    mapOf(alice to 50.0, bob to 20.0))

                assertTrue(result is SharingResult.ValidationError)
            }

            @Test
            fun `shares expense distributes proportionally`() {
                mediator.addExpenseByShares("Groceries", 100.0, alice,
                    mapOf(alice to 2.0, bob to 2.0, charlie to 1.0))

                assertAmountEquals(40.0, mediator.getDebtsOf(bob)[alice] ?: 0.0)
                assertAmountEquals(20.0, mediator.getDebtsOf(charlie)[alice] ?: 0.0)
            }

            @Test
            fun `shares rejects zero shares`() {
                val result = mediator.addExpenseByShares("Bad", 100.0, alice,
                    mapOf(alice to 1.0, bob to 0.0))

                assertTrue(result is SharingResult.ValidationError)
            }

            @Test
            fun `payment reduces debt`() {
                mediator.addExpenseEqual("Dinner", 100.0, alice, listOf(alice, bob))
                assertTrue(mediator.recordPayment(bob, alice, 20.0))

                assertAmountEquals(30.0, mediator.getDebtsOf(bob)[alice] ?: 0.0)
            }

            @Test
            fun `full payment clears debt`() {
                mediator.addExpenseEqual("Coffee", 20.0, alice, listOf(alice, bob))
                mediator.recordPayment(bob, alice, 10.0)

                assertTrue(mediator.getDebtsOf(bob).isEmpty())
            }

            @Test
            fun `payment fails when no debt`() {
                assertFalse(mediator.recordPayment(bob, alice, 50.0))
            }

            @Test
            fun `payment to self fails`() {
                assertFalse(mediator.recordPayment(alice, alice, 10.0))
            }

            @Test
            fun `generate settlements minimizes transactions`() {
                mediator.addExpenseEqual("Dinner", 90.0, alice, listOf(alice, bob, charlie))
                mediator.addExpenseEqual("Movie", 60.0, bob, listOf(alice, bob, charlie))

                val result = mediator.generateSettlements()
                assertTrue(result is SettlementResult.Success)
                assertTrue((result as SettlementResult.Success).settlements.size <= 2)
            }

            @Test
            fun `no settlements when balanced`() {
                val result = mediator.generateSettlements()
                assertTrue(result is SettlementResult.NoSettlementsNeeded)
            }

            @Test
            fun `net balance reflects transactions`() {
                mediator.addExpenseEqual("Dinner", 100.0, alice, listOf(alice, bob))

                assertAmountEquals(50.0, mediator.getNetBalance(alice))
                assertAmountEquals(-50.0, mediator.getNetBalance(bob))
            }

            @Test
            fun `circular debts cancel`() {
                mediator.addExpenseEqual("A", 60.0, alice, listOf(alice, bob, charlie))
                mediator.addExpenseEqual("B", 60.0, bob, listOf(alice, bob, charlie))
                mediator.addExpenseEqual("C", 60.0, charlie, listOf(alice, bob, charlie))

                assertAmountEquals(0.0, mediator.getNetBalance(alice))
                assertAmountEquals(0.0, mediator.getNetBalance(bob))
                assertAmountEquals(0.0, mediator.getNetBalance(charlie))
            }

            @Test
            fun `minimum settlement amount filters small debts`() {
                mediator.setMinimumSettlementAmount(5.0)
                mediator.addExpenseEqual("Coffee", 6.0, alice, listOf(alice, bob))

                val result = mediator.generateSettlements()
                if (result is SettlementResult.Success) {
                    result.settlements.forEach { assertTrue(it.amount >= 5.0) }
                }
            }
        }

        @Nested
        @DisplayName("Observer Notifications")
        inner class ObserverNotificationTests {

            private lateinit var mediator: SettlementMediator
            private lateinit var notifier: NotificationObserver

            @BeforeEach
            fun setup() {
                mediator = SettlementMediator()
                notifier = NotificationObserver()
                mediator.addObserver(notifier)
            }

            @Test
            fun `expense triggers notifications for participants`() {
                mediator.addExpenseEqual("Lunch", 30.0, alice, listOf(alice, bob, charlie))

                val bobNotifications = notifier.getNotificationsForUser(bob)
                assertTrue(bobNotifications.any { it.type == NotificationType.EXPENSE_ADDED })
            }

            @Test
            fun `payer gets notification about owed amounts`() {
                mediator.addExpenseEqual("Dinner", 90.0, alice, listOf(alice, bob, charlie))

                val aliceNotifications = notifier.getNotificationsForUser(alice)
                assertTrue(aliceNotifications.any {
                    it.type == NotificationType.EXPENSE_ADDED && it.message.contains("Others owe you")
                })
            }

            @Test
            fun `balance change notifications sent`() {
                mediator.addExpenseEqual("Test", 100.0, alice, listOf(alice, bob))

                val bobNotifications = notifier.getNotificationsForUser(bob)
                assertTrue(bobNotifications.any { it.type == NotificationType.BALANCE_CHANGED })
            }

            @Test
            fun `member joined notification sent`() {
                mediator.createGroup("Trip", listOf(alice, bob))

                val aliceNotifications = notifier.getNotificationsForUser(alice)
                assertTrue(aliceNotifications.any { it.type == NotificationType.MEMBER_JOINED })
            }

            @Test
            fun `settlement generation triggers notifications`() {
                mediator.addExpenseEqual("Big dinner", 200.0, alice, listOf(alice, bob))
                notifier.clearNotifications()

                mediator.generateSettlements()

                val bobNotifications = notifier.getNotificationsForUser(bob)
                assertTrue(bobNotifications.any { it.type == NotificationType.SETTLEMENT_SUGGESTED })
            }

            @Test
            fun `multiple observers receive same events`() {
                val secondNotifier = NotificationObserver()
                mediator.addObserver(secondNotifier)

                mediator.addExpenseEqual("Shared", 60.0, alice, listOf(alice, bob))

                assertEquals(
                    notifier.getNotifications().size,
                    secondNotifier.getNotifications().size
                )
            }

            @Test
            fun `removed observer stops receiving events`() {
                mediator.removeObserver(notifier)
                mediator.addExpenseEqual("Test", 50.0, alice, listOf(alice, bob))

                assertTrue(notifier.getNotifications().isEmpty())
            }
        }

        @Nested
        @DisplayName("Balance Auditor")
        inner class BalanceAuditorTests {

            private lateinit var mediator: SettlementMediator
            private lateinit var auditor: BalanceAuditor

            @BeforeEach
            fun setup() {
                mediator = SettlementMediator()
                auditor = BalanceAuditor()
                mediator.addObserver(auditor)
            }

            @Test
            fun `expense creates audit snapshot`() {
                mediator.addExpenseEqual("Lunch", 30.0, alice, listOf(alice, bob))

                val snapshots = auditor.getSnapshots()
                assertEquals(1, snapshots.size)
                assertTrue(snapshots[0].trigger.contains("Lunch"))
            }

            @Test
            fun `settlement creates audit snapshot`() {
                mediator.addExpenseEqual("Dinner", 100.0, alice, listOf(alice, bob))
                mediator.generateSettlements()

                val snapshots = auditor.getSnapshots()
                assertTrue(snapshots.any { it.trigger.contains("Settlement") })
            }
        }

        @Nested
        @DisplayName("Group Management")
        inner class GroupManagementTests {

            private lateinit var mediator: SettlementMediator

            @BeforeEach
            fun setup() { mediator = SettlementMediator() }

            @Test
            fun `create group with members`() {
                val group = mediator.createGroup("Roommates", listOf(alice, bob, charlie))

                assertEquals("Roommates", group.name)
                assertEquals(3, group.members.size)
            }

            @Test
            fun `add member to group`() {
                val group = mediator.createGroup("Trip", listOf(alice, bob))
                assertTrue(mediator.addMemberToGroup(group.id, charlie))

                assertEquals(3, group.members.size)
            }

            @Test
            fun `duplicate member not added`() {
                val group = mediator.createGroup("Trip", listOf(alice, bob))
                assertFalse(mediator.addMemberToGroup(group.id, alice))
            }

            @Test
            fun `group expenses tracked`() {
                val group = mediator.createGroup("Trip", listOf(alice, bob))
                mediator.addExpenseEqual("Hotel", 200.0, alice, listOf(alice, bob), groupId = group.id)
                mediator.addExpenseEqual("Unrelated", 50.0, alice, listOf(alice, charlie))

                assertEquals(1, mediator.getExpensesByGroup(group.id).size)
            }
        }
    }

    @Nested
    @DisplayName("Event Sourcing Approach")
    inner class EventSourcingTests {

        @Nested
        @DisplayName("Core Event Sourcing Operations")
        inner class CoreEventSourcingTests {

            private lateinit var sharing: EventSourcingSharing

            @BeforeEach
            fun setup() { sharing = EventSourcingSharing() }

            @Test
            fun `equal expense creates correct debts`() {
                sharing.addExpenseEqual("Dinner", 90.0, alice, listOf(alice, bob, charlie))

                assertAmountEquals(30.0, sharing.getDebtsOf(bob)[alice] ?: 0.0)
                assertAmountEquals(30.0, sharing.getDebtsOf(charlie)[alice] ?: 0.0)
            }

            @Test
            fun `percentage expense distributes correctly`() {
                sharing.addExpensePercentage("Party", 200.0, alice,
                    mapOf(alice to 50.0, bob to 30.0, charlie to 20.0))

                assertAmountEquals(60.0, sharing.getDebtsOf(bob)[alice] ?: 0.0)
                assertAmountEquals(40.0, sharing.getDebtsOf(charlie)[alice] ?: 0.0)
            }

            @Test
            fun `exact expense records correctly`() {
                val result = sharing.addExpenseExact("Supplies", 100.0, alice,
                    mapOf(alice to 40.0, bob to 35.0, charlie to 25.0))

                assertTrue(result is SharingResult.Success)
                assertAmountEquals(35.0, sharing.getDebtsOf(bob)[alice] ?: 0.0)
            }

            @Test
            fun `shares expense distributes proportionally`() {
                sharing.addExpenseByShares("Groceries", 90.0, alice,
                    mapOf(alice to 3.0, bob to 2.0, charlie to 1.0))

                assertAmountEquals(30.0, sharing.getDebtsOf(bob)[alice] ?: 0.0)
                assertAmountEquals(15.0, sharing.getDebtsOf(charlie)[alice] ?: 0.0)
            }

            @Test
            fun `payment reduces debt`() {
                sharing.addExpenseEqual("Dinner", 100.0, alice, listOf(alice, bob))
                assertTrue(sharing.recordPayment(bob, alice, 20.0))

                assertAmountEquals(30.0, sharing.getDebtsOf(bob)[alice] ?: 0.0)
            }

            @Test
            fun `full payment clears debt`() {
                sharing.addExpenseEqual("Coffee", 20.0, alice, listOf(alice, bob))
                sharing.recordPayment(bob, alice, 10.0)

                assertTrue(sharing.getDebtsOf(bob).isEmpty())
            }

            @Test
            fun `payment fails when no debt`() {
                assertFalse(sharing.recordPayment(bob, alice, 50.0))
            }

            @Test
            fun `simplify debts minimizes transactions`() {
                sharing.addExpenseEqual("Dinner", 90.0, alice, listOf(alice, bob, charlie))
                sharing.addExpenseEqual("Movie", 60.0, bob, listOf(alice, bob, charlie))

                val result = sharing.simplifyDebts()
                assertTrue(result is SettlementResult.Success)
                assertTrue((result as SettlementResult.Success).settlements.size <= 2)
            }

            @Test
            fun `no settlements when balanced`() {
                val result = sharing.simplifyDebts()
                assertTrue(result is SettlementResult.NoSettlementsNeeded)
            }

            @Test
            fun `net balance reflects transactions`() {
                sharing.addExpenseEqual("Dinner", 100.0, alice, listOf(alice, bob))

                assertAmountEquals(50.0, sharing.getNetBalance(alice))
                assertAmountEquals(-50.0, sharing.getNetBalance(bob))
            }

            @Test
            fun `circular debts cancel`() {
                sharing.addExpenseEqual("A", 60.0, alice, listOf(alice, bob, charlie))
                sharing.addExpenseEqual("B", 60.0, bob, listOf(alice, bob, charlie))
                sharing.addExpenseEqual("C", 60.0, charlie, listOf(alice, bob, charlie))

                assertAmountEquals(0.0, sharing.getNetBalance(alice))
                assertAmountEquals(0.0, sharing.getNetBalance(bob))
                assertAmountEquals(0.0, sharing.getNetBalance(charlie))
            }

            @Test
            fun `validation rejects negative amount`() {
                val result = sharing.addExpenseEqual("Bad", -10.0, alice, listOf(alice, bob))
                assertTrue(result is SharingResult.ValidationError)
            }

            @Test
            fun `validation rejects empty participants`() {
                val result = sharing.addExpenseEqual("Bad", 100.0, alice, emptyList())
                assertTrue(result is SharingResult.ValidationError)
            }
        }

        @Nested
        @DisplayName("Event Log and Time Travel")
        inner class EventLogTests {

            private lateinit var sharing: EventSourcingSharing

            @BeforeEach
            fun setup() { sharing = EventSourcingSharing() }

            @Test
            fun `events are recorded in order`() {
                sharing.addExpenseEqual("First", 30.0, alice, listOf(alice, bob))
                sharing.addExpenseEqual("Second", 40.0, bob, listOf(alice, bob))
                sharing.recordPayment(alice, bob, 10.0)

                val events = sharing.getEventLog()
                assertEquals(3, events.size)
                assertTrue(events[0] is SharingEvent.ExpenseAdded)
                assertTrue(events[1] is SharingEvent.ExpenseAdded)
                assertTrue(events[2] is SharingEvent.PaymentMade)
            }

            @Test
            fun `event count tracks all events`() {
                sharing.createGroup("Trip", listOf(alice, bob))
                sharing.addExpenseEqual("Hotel", 200.0, alice, listOf(alice, bob))
                sharing.recordPayment(bob, alice, 50.0)

                assertEquals(3, sharing.getEventCount())
            }

            @Test
            fun `group events are recorded`() {
                sharing.createGroup("Trip", listOf(alice, bob))
                sharing.addMemberToGroup(
                    sharing.getEventLog().filterIsInstance<SharingEvent.GroupCreated>().first().groupId,
                    charlie
                )

                val events = sharing.getEventLog()
                assertTrue(events.any { it is SharingEvent.GroupCreated })
                assertTrue(events.any { it is SharingEvent.MemberJoined })
            }

            @Test
            fun `time travel returns historical balances`() {
                sharing.addExpenseEqual("Early", 100.0, alice, listOf(alice, bob))
                val midpoint = LocalDateTime.now()

                Thread.sleep(10)
                sharing.addExpenseEqual("Late", 50.0, bob, listOf(alice, bob))

                val historicalBalances = sharing.getBalancesAtTime(midpoint)
                val currentBalance = sharing.getNetBalance(alice)

                assertAmountEquals(50.0, historicalBalances[alice] ?: 0.0)
                assertAmountEquals(25.0, currentBalance)
            }

            @Test
            fun `debt forgiveness reduces debt and records event`() {
                sharing.addExpenseEqual("Dinner", 100.0, alice, listOf(alice, bob))
                assertTrue(sharing.forgiveDebt(alice, bob, 20.0))

                assertAmountEquals(30.0, sharing.getDebtsOf(bob)[alice] ?: 0.0)
                assertTrue(sharing.getEventLog().any { it is SharingEvent.DebtForgiven })
            }

            @Test
            fun `forgive more than owed forgives only actual debt`() {
                sharing.addExpenseEqual("Coffee", 20.0, alice, listOf(alice, bob))
                sharing.forgiveDebt(alice, bob, 100.0)

                assertTrue(sharing.getDebtsOf(bob).isEmpty())
            }

            @Test
            fun `forgive fails when no debt exists`() {
                assertFalse(sharing.forgiveDebt(alice, bob, 50.0))
            }
        }

        @Nested
        @DisplayName("Projection Operations")
        inner class ProjectionTests {

            private lateinit var sharing: EventSourcingSharing

            @BeforeEach
            fun setup() { sharing = EventSourcingSharing() }

            @Test
            fun `expense projection tracks expenses`() {
                sharing.addExpenseEqual("A", 50.0, alice, listOf(alice, bob))
                sharing.addExpenseEqual("B", 30.0, bob, listOf(alice, bob))

                assertEquals(2, sharing.getExpenses().size)
                assertAmountEquals(80.0, sharing.getTotalExpenses())
            }

            @Test
            fun `expense projection filters by group`() {
                val group = sharing.createGroup("Trip", listOf(alice, bob))
                sharing.addExpenseEqual("Hotel", 200.0, alice, listOf(alice, bob), groupId = group.id)
                sharing.addExpenseEqual("Other", 50.0, alice, listOf(alice, charlie))

                assertEquals(1, sharing.getExpensesByGroup(group.id).size)
            }

            @Test
            fun `group projection tracks membership`() {
                val group = sharing.createGroup("Team", listOf(alice, bob))
                sharing.addMemberToGroup(group.id, charlie)

                val updatedGroup = sharing.groupProjection.getGroup(group.id)!!
                assertEquals(3, updatedGroup.members.size)
            }

            @Test
            fun `member removal tracked`() {
                val group = sharing.createGroup("Team", listOf(alice, bob, charlie))
                sharing.removeMemberFromGroup(group.id, charlie)

                val updatedGroup = sharing.groupProjection.getGroup(group.id)!!
                assertEquals(2, updatedGroup.members.size)
                assertFalse(updatedGroup.members.contains(charlie))
            }

            @Test
            fun `balance projection handles complex scenario`() {
                sharing.addExpenseEqual("Hotel", 400.0, alice, listOf(alice, bob, charlie, diana))
                sharing.addExpenseEqual("Gas", 80.0, bob, listOf(alice, bob, charlie, diana))
                sharing.addExpenseEqual("Food", 120.0, charlie, listOf(alice, bob, charlie, diana))

                val result = sharing.simplifyDebts()
                assertTrue(result is SettlementResult.Success)
                val settlements = (result as SettlementResult.Success).settlements
                assertTrue(settlements.size <= 3)

                val netSum = listOf(alice, bob, charlie, diana).sumOf { sharing.getNetBalance(it) }
                assertAmountEquals(0.0, netSum)
            }
        }
    }

    @Nested
    @DisplayName("Cross-Approach Consistency")
    inner class CrossApproachTests {

        @Test
        fun `all approaches produce same net balances for equal split`() {
            val graphSharing = GraphSimplificationSharing()
            val mediator = SettlementMediator()
            val eventSharing = EventSourcingSharing()

            graphSharing.addExpenseEqual("Dinner", 120.0, alice, listOf(alice, bob, charlie))
            mediator.addExpenseEqual("Dinner", 120.0, alice, listOf(alice, bob, charlie))
            eventSharing.addExpenseEqual("Dinner", 120.0, alice, listOf(alice, bob, charlie))

            assertAmountEquals(graphSharing.getNetBalance(alice), mediator.getNetBalance(alice))
            assertAmountEquals(graphSharing.getNetBalance(alice), eventSharing.getNetBalance(alice))

            assertAmountEquals(graphSharing.getNetBalance(bob), mediator.getNetBalance(bob))
            assertAmountEquals(graphSharing.getNetBalance(bob), eventSharing.getNetBalance(bob))
        }

        @Test
        fun `all approaches produce same net balances after payment`() {
            val graphSharing = GraphSimplificationSharing()
            val mediator = SettlementMediator()
            val eventSharing = EventSourcingSharing()

            graphSharing.addExpenseEqual("Dinner", 100.0, alice, listOf(alice, bob))
            mediator.addExpenseEqual("Dinner", 100.0, alice, listOf(alice, bob))
            eventSharing.addExpenseEqual("Dinner", 100.0, alice, listOf(alice, bob))

            graphSharing.recordPayment(bob, alice, 20.0)
            mediator.recordPayment(bob, alice, 20.0)
            eventSharing.recordPayment(bob, alice, 20.0)

            assertAmountEquals(graphSharing.getNetBalance(alice), mediator.getNetBalance(alice))
            assertAmountEquals(graphSharing.getNetBalance(alice), eventSharing.getNetBalance(alice))
        }

        @Test
        fun `all approaches handle four person scenario consistently`() {
            val graphSharing = GraphSimplificationSharing()
            val mediator = SettlementMediator()
            val eventSharing = EventSourcingSharing()

            val participants = listOf(alice, bob, charlie, diana)

            graphSharing.addExpenseEqual("Hotel", 400.0, alice, participants)
            graphSharing.addExpenseEqual("Gas", 80.0, bob, participants)

            mediator.addExpenseEqual("Hotel", 400.0, alice, participants)
            mediator.addExpenseEqual("Gas", 80.0, bob, participants)

            eventSharing.addExpenseEqual("Hotel", 400.0, alice, participants)
            eventSharing.addExpenseEqual("Gas", 80.0, bob, participants)

            participants.forEach { user ->
                assertAmountEquals(
                    graphSharing.getNetBalance(user),
                    mediator.getNetBalance(user),
                    "Mismatch for ${user.name} between graph and mediator"
                )
                assertAmountEquals(
                    graphSharing.getNetBalance(user),
                    eventSharing.getNetBalance(user),
                    "Mismatch for ${user.name} between graph and event sourcing"
                )
            }
        }

        @Test
        fun `settlement counts are comparable across approaches`() {
            val graphSharing = GraphSimplificationSharing()
            val mediator = SettlementMediator()
            val eventSharing = EventSourcingSharing()

            graphSharing.addExpenseEqual("A", 90.0, alice, listOf(alice, bob, charlie))
            graphSharing.addExpenseEqual("B", 60.0, bob, listOf(alice, bob, charlie))
            graphSharing.addExpenseEqual("C", 30.0, charlie, listOf(alice, bob, charlie))

            mediator.addExpenseEqual("A", 90.0, alice, listOf(alice, bob, charlie))
            mediator.addExpenseEqual("B", 60.0, bob, listOf(alice, bob, charlie))
            mediator.addExpenseEqual("C", 30.0, charlie, listOf(alice, bob, charlie))

            eventSharing.addExpenseEqual("A", 90.0, alice, listOf(alice, bob, charlie))
            eventSharing.addExpenseEqual("B", 60.0, bob, listOf(alice, bob, charlie))
            eventSharing.addExpenseEqual("C", 30.0, charlie, listOf(alice, bob, charlie))

            val graphResult = graphSharing.simplifyDebts()
            val mediatorResult = mediator.generateSettlements()
            val eventResult = eventSharing.simplifyDebts()

            fun settlementCount(result: SettlementResult): Int = when (result) {
                is SettlementResult.Success -> result.settlements.size
                is SettlementResult.NoSettlementsNeeded -> 0
            }

            assertTrue(settlementCount(graphResult) <= 2)
            assertTrue(settlementCount(mediatorResult) <= 2)
            assertTrue(settlementCount(eventResult) <= 2)
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCaseTests {

        @Test
        fun `single person expense has no debts`() {
            val sharing = GraphSimplificationSharing()
            sharing.addExpenseEqual("Solo", 50.0, alice, listOf(alice))

            assertTrue(sharing.getDebtsOf(alice).isEmpty())
            assertTrue(sharing.getCreditsOf(alice).isEmpty())
        }

        @Test
        fun `very small amount split correctly`() {
            val sharing = GraphSimplificationSharing()
            sharing.addExpenseEqual("Tiny", 0.03, alice, listOf(alice, bob, charlie))

            val totalDebt = sharing.getDebtsOf(bob).values.sum() + sharing.getDebtsOf(charlie).values.sum()
            assertTrue(totalDebt <= 0.03)
        }

        @Test
        fun `very large amount handled`() {
            val sharing = GraphSimplificationSharing()
            sharing.addExpenseEqual("Huge", 1_000_000.0, alice, listOf(alice, bob))

            assertAmountEquals(500_000.0, sharing.getDebtsOf(bob)[alice] ?: 0.0)
        }

        @Test
        fun `many small transactions accumulate correctly`() {
            val sharing = GraphSimplificationSharing()
            repeat(20) {
                sharing.addExpenseEqual("Expense $it", 10.0, alice, listOf(alice, bob))
            }

            assertAmountEquals(100.0, sharing.getDebtsOf(bob)[alice] ?: 0.0)
        }

        @Test
        fun `overpayment creates reverse debt`() {
            val sharing = GraphSimplificationSharing()
            sharing.addExpenseEqual("Dinner", 60.0, alice, listOf(alice, bob))
            sharing.recordPayment(bob, alice, 40.0)

            assertAmountEquals(10.0, sharing.getDebtsOf(alice)[bob] ?: 0.0)
        }

        @Test
        fun `percentage split with 100 percent to one person`() {
            val sharing = GraphSimplificationSharing()
            val result = sharing.addExpensePercentage("Solo", 50.0, alice, mapOf(alice to 100.0))

            assertTrue(result is SharingResult.Success)
            assertTrue(sharing.getDebtsOf(alice).isEmpty())
        }

        @Test
        fun `event sourcing forgive self fails`() {
            val sharing = EventSourcingSharing()
            assertFalse(sharing.forgiveDebt(alice, alice, 50.0))
        }

        @Test
        fun `mediator add member to nonexistent group fails`() {
            val mediator = SettlementMediator()
            assertFalse(mediator.addMemberToGroup("nonexistent", alice))
        }

        @Test
        fun `graph sharing remove member from nonexistent group`() {
            val sharing = EventSourcingSharing()
            assertFalse(sharing.removeMemberFromGroup("nonexistent", alice))
        }

        @Test
        fun `partial payments tracked correctly across approaches`() {
            val sharing = GraphSimplificationSharing()
            sharing.addExpenseEqual("Big", 100.0, alice, listOf(alice, bob))

            sharing.recordPayment(bob, alice, 10.0)
            sharing.recordPayment(bob, alice, 15.0)
            sharing.recordPayment(bob, alice, 5.0)

            assertAmountEquals(20.0, sharing.getDebtsOf(bob)[alice] ?: 0.0)
        }
    }
}
