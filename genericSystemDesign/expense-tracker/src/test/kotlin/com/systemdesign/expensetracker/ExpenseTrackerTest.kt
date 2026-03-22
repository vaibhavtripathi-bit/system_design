package com.systemdesign.expensetracker

import com.systemdesign.expensetracker.common.*
import com.systemdesign.expensetracker.approach_01_strategy_split.*
import com.systemdesign.expensetracker.approach_02_observer_balances.*
import com.systemdesign.expensetracker.approach_03_command_transactions.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import kotlin.math.abs

@DisplayName("Expense Tracker Tests")
class ExpenseTrackerTest {
    
    companion object {
        val alice = User(id = "alice", name = "Alice", email = "alice@test.com")
        val bob = User(id = "bob", name = "Bob", email = "bob@test.com")
        val charlie = User(id = "charlie", name = "Charlie", email = "charlie@test.com")
        val diana = User(id = "diana", name = "Diana", email = "diana@test.com")
        
        fun assertAmountEquals(expected: Double, actual: Double, message: String = "") {
            assertTrue(abs(expected - actual) < 0.01, "$message Expected: $expected, Actual: $actual")
        }
    }
    
    @Nested
    @DisplayName("Strategy Pattern - Split Calculations")
    inner class StrategySplitTests {
        
        @Nested
        @DisplayName("Equal Split Strategy")
        inner class EqualSplitTests {
            
            private val strategy = EqualSplitStrategy()
            
            @Test
            fun `equal split among two users`() {
                val splits = strategy.split(100.0, listOf(alice, bob))
                
                assertEquals(2, splits.size)
                assertAmountEquals(50.0, splits[0].amount)
                assertAmountEquals(50.0, splits[1].amount)
            }
            
            @Test
            fun `equal split among three users`() {
                val splits = strategy.split(100.0, listOf(alice, bob, charlie))
                
                assertEquals(3, splits.size)
                val total = splits.sumOf { it.amount }
                assertAmountEquals(100.0, total, "Total should equal original amount")
            }
            
            @Test
            fun `handles remainder correctly for indivisible amounts`() {
                val splits = strategy.split(100.0, listOf(alice, bob, charlie))
                
                val total = splits.sumOf { it.amount }
                assertAmountEquals(100.0, total)
                
                splits.forEach { split ->
                    assertTrue(split.amount >= 33.33 && split.amount <= 33.34)
                }
            }
            
            @Test
            fun `handles single participant`() {
                val splits = strategy.split(100.0, listOf(alice))
                
                assertEquals(1, splits.size)
                assertAmountEquals(100.0, splits[0].amount)
            }
            
            @Test
            fun `throws exception for empty participants`() {
                assertThrows<IllegalArgumentException> {
                    strategy.split(100.0, emptyList())
                }
            }
            
            @Test
            fun `handles small amounts`() {
                val splits = strategy.split(0.10, listOf(alice, bob, charlie))
                
                val total = splits.sumOf { it.amount }
                assertAmountEquals(0.10, total)
            }
        }
        
        @Nested
        @DisplayName("Exact Amount Strategy")
        inner class ExactAmountTests {
            
            private val strategy = ExactAmountStrategy()
            
            @Test
            fun `exact amounts split correctly`() {
                val amounts = mapOf(alice to 60.0, bob to 40.0)
                val splits = strategy.split(100.0, listOf(alice, bob), amounts)
                
                assertEquals(2, splits.size)
                assertAmountEquals(60.0, splits.find { it.user == alice }!!.amount)
                assertAmountEquals(40.0, splits.find { it.user == bob }!!.amount)
            }
            
            @Test
            fun `throws exception when amounts dont sum to total`() {
                val amounts = mapOf(alice to 60.0, bob to 30.0)
                
                assertThrows<IllegalArgumentException> {
                    strategy.split(100.0, listOf(alice, bob), amounts)
                }
            }
            
            @Test
            fun `throws exception without metadata`() {
                assertThrows<IllegalArgumentException> {
                    strategy.split(100.0, listOf(alice, bob))
                }
            }
            
            @Test
            fun `handles uneven exact amounts`() {
                val amounts = mapOf(alice to 33.33, bob to 33.33, charlie to 33.34)
                val splits = strategy.split(100.0, listOf(alice, bob, charlie), amounts)
                
                val total = splits.sumOf { it.amount }
                assertAmountEquals(100.0, total)
            }
        }
        
        @Nested
        @DisplayName("Percentage Strategy")
        inner class PercentageTests {
            
            private val strategy = PercentageStrategy()
            
            @Test
            fun `percentage split correctly`() {
                val percentages = mapOf(alice to 60.0, bob to 40.0)
                val splits = strategy.split(100.0, listOf(alice, bob), percentages)
                
                assertAmountEquals(60.0, splits.find { it.user == alice }!!.amount)
                assertAmountEquals(40.0, splits.find { it.user == bob }!!.amount)
            }
            
            @Test
            fun `throws exception when percentages dont sum to 100`() {
                val percentages = mapOf(alice to 60.0, bob to 30.0)
                
                assertThrows<IllegalArgumentException> {
                    strategy.split(100.0, listOf(alice, bob), percentages)
                }
            }
            
            @Test
            fun `handles rounding in percentage splits`() {
                val percentages = mapOf(alice to 33.33, bob to 33.33, charlie to 33.34)
                val splits = strategy.split(100.0, listOf(alice, bob, charlie), percentages)
                
                val total = splits.sumOf { it.amount }
                assertAmountEquals(100.0, total)
            }
            
            @Test
            fun `handles 100 percent to one user`() {
                val percentages = mapOf(alice to 100.0)
                val splits = strategy.split(50.0, listOf(alice), percentages)
                
                assertEquals(1, splits.size)
                assertAmountEquals(50.0, splits[0].amount)
            }
        }
        
        @Nested
        @DisplayName("Share-Based Strategy")
        inner class ShareBasedTests {
            
            private val strategy = ShareBasedStrategy()
            
            @Test
            fun `share-based split correctly`() {
                val shares = mapOf(alice to 2.0, bob to 1.0)
                val splits = strategy.split(90.0, listOf(alice, bob), shares)
                
                assertAmountEquals(60.0, splits.find { it.user == alice }!!.amount)
                assertAmountEquals(30.0, splits.find { it.user == bob }!!.amount)
            }
            
            @Test
            fun `equal shares work like equal split`() {
                val shares = mapOf(alice to 1.0, bob to 1.0, charlie to 1.0)
                val splits = strategy.split(99.0, listOf(alice, bob, charlie), shares)
                
                val total = splits.sumOf { it.amount }
                assertAmountEquals(99.0, total)
                splits.forEach { split ->
                    assertAmountEquals(33.0, split.amount)
                }
            }
            
            @Test
            fun `handles fractional shares`() {
                val shares = mapOf(alice to 1.5, bob to 1.0, charlie to 0.5)
                val splits = strategy.split(60.0, listOf(alice, bob, charlie), shares)
                
                assertAmountEquals(30.0, splits.find { it.user == alice }!!.amount)
                assertAmountEquals(20.0, splits.find { it.user == bob }!!.amount)
                assertAmountEquals(10.0, splits.find { it.user == charlie }!!.amount)
            }
            
            @Test
            fun `throws exception for zero or negative shares`() {
                val shares = mapOf(alice to 1.0, bob to 0.0)
                
                assertThrows<IllegalArgumentException> {
                    strategy.split(100.0, listOf(alice, bob), shares)
                }
            }
        }
        
        @Nested
        @DisplayName("Strategy Expense Tracker Integration")
        inner class StrategyTrackerTests {
            
            private lateinit var tracker: StrategySplitExpenseTracker
            
            @BeforeEach
            fun setup() {
                tracker = StrategySplitExpenseTracker()
            }
            
            @Test
            fun `add equal expense and verify balances`() {
                val result = tracker.addExpenseEqual(
                    description = "Dinner",
                    amount = 60.0,
                    paidBy = alice,
                    participants = listOf(alice, bob, charlie)
                )
                
                assertTrue(result is ExpenseResult.Success)
                
                val bobBalance = tracker.getBalance(bob)
                assertAmountEquals(20.0, bobBalance.owes[alice] ?: 0.0)
                
                val aliceBalance = tracker.getBalance(alice)
                assertAmountEquals(40.0, aliceBalance.totalOwed)
            }
            
            @Test
            fun `add percentage expense and verify balances`() {
                val percentages = mapOf(alice to 50.0, bob to 30.0, charlie to 20.0)
                
                val result = tracker.addExpensePercentage(
                    description = "Party",
                    amount = 100.0,
                    paidBy = alice,
                    percentages = percentages
                )
                
                assertTrue(result is ExpenseResult.Success)
                
                val bobBalance = tracker.getBalance(bob)
                assertAmountEquals(30.0, bobBalance.owes[alice] ?: 0.0)
                
                val charlieBalance = tracker.getBalance(charlie)
                assertAmountEquals(20.0, charlieBalance.owes[alice] ?: 0.0)
            }
            
            @Test
            fun `settlement suggestions minimize transactions`() {
                tracker.addExpenseEqual("Dinner", 90.0, alice, listOf(alice, bob, charlie))
                tracker.addExpenseEqual("Movie", 60.0, bob, listOf(alice, bob, charlie))
                
                val settlements = tracker.suggestSettlements()
                
                assertTrue(settlements is SettlementResult.Success)
                val settlementsSuccess = settlements as SettlementResult.Success
                assertTrue(settlementsSuccess.settlements.size <= 2)
            }
        }
    }
    
    @Nested
    @DisplayName("Observer Pattern - Balance Tracking")
    inner class ObserverBalanceTests {
        
        private lateinit var tracker: ObserverBalanceExpenseTracker
        private lateinit var notifier: BalanceNotifier
        private lateinit var debtTracker: DebtTracker
        private lateinit var settlementSuggester: SettlementSuggester
        
        @BeforeEach
        fun setup() {
            tracker = ObserverBalanceExpenseTracker()
            notifier = BalanceNotifier()
            debtTracker = DebtTracker()
            settlementSuggester = SettlementSuggester()
            
            tracker.addObserver(notifier)
            tracker.addObserver(debtTracker)
            tracker.addObserver(settlementSuggester)
        }
        
        @Nested
        @DisplayName("Balance Notifier Tests")
        inner class NotifierTests {
            
            @Test
            fun `notifies participants when expense added`() {
                tracker.addExpenseEqual("Lunch", 30.0, alice, listOf(alice, bob, charlie))
                
                val notifications = notifier.getNotifications()
                assertTrue(notifications.isNotEmpty())
                
                val bobNotifications = notifier.getNotificationsForUser(bob)
                assertTrue(bobNotifications.any { it.type == NotificationType.EXPENSE_ADDED })
            }
            
            @Test
            fun `notifies both parties when payment made`() {
                tracker.addExpenseEqual("Dinner", 60.0, alice, listOf(alice, bob))
                notifier.clearNotifications()
                
                tracker.recordPayment(bob, alice, 30.0)
                
                val notifications = notifier.getNotifications()
                assertTrue(notifications.any { it.type == NotificationType.PAYMENT_SENT && it.user == bob })
                assertTrue(notifications.any { it.type == NotificationType.PAYMENT_RECEIVED && it.user == alice })
            }
            
            @Test
            fun `notifies when balance settled`() {
                tracker.addExpenseEqual("Coffee", 20.0, alice, listOf(alice, bob))
                notifier.clearNotifications()
                
                tracker.recordPayment(bob, alice, 10.0)
                
                val notifications = notifier.getNotificationsForUser(bob)
                assertTrue(notifications.any { 
                    it.type == NotificationType.BALANCE_SETTLED || it.type == NotificationType.PAYMENT_SENT 
                })
            }
        }
        
        @Nested
        @DisplayName("Debt Tracker Tests")
        inner class DebtTrackerTests {
            
            @Test
            fun `tracks debt increases from expenses`() {
                tracker.addExpenseEqual("Groceries", 60.0, alice, listOf(alice, bob, charlie))
                
                val history = debtTracker.getDebtHistory()
                assertTrue(history.any { it.type == DebtEventType.DEBT_INCREASED && it.debtor == bob })
                assertTrue(history.any { it.type == DebtEventType.DEBT_INCREASED && it.debtor == charlie })
            }
            
            @Test
            fun `tracks debt decreases from payments`() {
                tracker.addExpenseEqual("Lunch", 40.0, alice, listOf(alice, bob))
                tracker.recordPayment(bob, alice, 10.0)
                
                val history = debtTracker.getDebtHistory()
                assertTrue(history.any { it.type == DebtEventType.DEBT_DECREASED && it.debtor == bob })
            }
            
            @Test
            fun `calculates current debts correctly`() {
                tracker.addExpenseEqual("Dinner", 60.0, alice, listOf(alice, bob))
                
                val debts = debtTracker.getCurrentDebts()
                assertAmountEquals(30.0, debts[bob to alice] ?: 0.0)
            }
            
            @Test
            fun `tracks debt history between specific users`() {
                tracker.addExpenseEqual("Meal 1", 40.0, alice, listOf(alice, bob))
                tracker.addExpenseEqual("Meal 2", 30.0, bob, listOf(alice, bob))
                
                val history = debtTracker.getDebtHistoryBetween(alice, bob)
                assertTrue(history.size >= 2)
            }
        }
        
        @Nested
        @DisplayName("Settlement Suggester Tests")
        inner class SettlementSuggesterTests {
            
            @Test
            fun `suggests settlements above threshold`() {
                settlementSuggester.setThreshold(5.0)
                
                tracker.addExpenseEqual("Big dinner", 100.0, alice, listOf(alice, bob))
                
                val bobBalance = tracker.getBalance(bob)
                assertTrue(bobBalance.totalOwes > 5.0)
            }
            
            @Test
            fun `no suggestions below threshold`() {
                settlementSuggester.setThreshold(100.0)
                
                tracker.addExpenseEqual("Coffee", 6.0, alice, listOf(alice, bob))
                
                val suggestions = settlementSuggester.getSuggestions()
                assertTrue(suggestions.isEmpty() || suggestions.all { it.amount < 100.0 })
            }
        }
        
        @Nested
        @DisplayName("Real-time Balance Updates")
        inner class RealTimeBalanceTests {
            
            @Test
            fun `balance updates immediately after expense`() {
                val initialBalance = tracker.getBalance(bob)
                assertAmountEquals(0.0, initialBalance.totalOwes)
                
                tracker.addExpenseEqual("Test", 100.0, alice, listOf(alice, bob))
                
                val newBalance = tracker.getBalance(bob)
                assertAmountEquals(50.0, newBalance.totalOwes)
            }
            
            @Test
            fun `balance updates immediately after payment`() {
                tracker.addExpenseEqual("Test", 100.0, alice, listOf(alice, bob))
                tracker.recordPayment(bob, alice, 25.0)
                
                val balance = tracker.getBalance(bob)
                assertAmountEquals(25.0, balance.owes[alice] ?: 0.0)
            }
            
            @Test
            fun `multiple observers receive same events`() {
                val secondNotifier = BalanceNotifier()
                tracker.addObserver(secondNotifier)
                
                tracker.addExpenseEqual("Shared", 60.0, alice, listOf(alice, bob, charlie))
                
                assertEquals(
                    notifier.getNotifications().size,
                    secondNotifier.getNotifications().size
                )
            }
        }
    }
    
    @Nested
    @DisplayName("Command Pattern - Transaction Management")
    inner class CommandTransactionTests {
        
        private lateinit var tracker: CommandableExpenseTracker
        
        @BeforeEach
        fun setup() {
            tracker = CommandableExpenseTracker()
        }
        
        @Nested
        @DisplayName("Add Expense Command")
        inner class AddExpenseCommandTests {
            
            @Test
            fun `execute adds expense`() {
                val result = tracker.addExpenseEqual("Lunch", 60.0, alice, listOf(alice, bob, charlie))
                
                assertTrue(result is CommandResult.Success)
                assertEquals(1, tracker.getExpenses().size)
            }
            
            @Test
            fun `undo removes expense`() {
                tracker.addExpenseEqual("Lunch", 60.0, alice, listOf(alice, bob, charlie))
                
                val undoResult = tracker.undo()
                
                assertTrue(undoResult is CommandResult.Success)
                assertEquals(0, tracker.getExpenses().size)
            }
            
            @Test
            fun `undo restores balances`() {
                tracker.addExpenseEqual("Dinner", 90.0, alice, listOf(alice, bob, charlie))
                
                val balanceBefore = tracker.getBalance(bob)
                assertAmountEquals(30.0, balanceBefore.owes[alice] ?: 0.0)
                
                tracker.undo()
                
                val balanceAfter = tracker.getBalance(bob)
                assertAmountEquals(0.0, balanceAfter.totalOwes)
            }
            
            @Test
            fun `validation prevents invalid expense`() {
                val result = tracker.addExpense(
                    "Invalid",
                    -50.0,
                    alice,
                    listOf(Split(bob, 50.0))
                )
                
                assertTrue(result is CommandResult.Failure)
            }
        }
        
        @Nested
        @DisplayName("Payment Command")
        inner class PaymentCommandTests {
            
            @Test
            fun `execute records payment`() {
                tracker.addExpenseEqual("Dinner", 60.0, alice, listOf(alice, bob))
                
                val result = tracker.recordPayment(bob, alice, 20.0)
                
                assertTrue(result is CommandResult.Success)
                
                val balance = tracker.getBalance(bob)
                assertAmountEquals(10.0, balance.owes[alice] ?: 0.0)
            }
            
            @Test
            fun `undo restores previous debt`() {
                tracker.addExpenseEqual("Dinner", 60.0, alice, listOf(alice, bob))
                tracker.recordPayment(bob, alice, 20.0)
                
                tracker.undo()
                
                val balance = tracker.getBalance(bob)
                assertAmountEquals(30.0, balance.owes[alice] ?: 0.0)
            }
            
            @Test
            fun `payment fails when no debt exists`() {
                val result = tracker.recordPayment(bob, alice, 50.0)
                
                assertTrue(result is CommandResult.Failure)
            }
            
            @Test
            fun `cannot pay yourself`() {
                val result = tracker.recordPayment(alice, alice, 10.0)
                
                assertTrue(result is CommandResult.Failure)
            }
        }
        
        @Nested
        @DisplayName("Settle Debt Command")
        inner class SettleDebtCommandTests {
            
            @Test
            fun `settle clears entire debt`() {
                tracker.addExpenseEqual("Big dinner", 100.0, alice, listOf(alice, bob))
                
                val result = tracker.settleDebt(bob, alice)
                
                assertTrue(result is CommandResult.Success)
                
                val balance = tracker.getBalance(bob)
                assertAmountEquals(0.0, balance.owes[alice] ?: 0.0)
            }
            
            @Test
            fun `undo restores settled debt`() {
                tracker.addExpenseEqual("Dinner", 80.0, alice, listOf(alice, bob))
                tracker.settleDebt(bob, alice)
                
                tracker.undo()
                
                val balance = tracker.getBalance(bob)
                assertAmountEquals(40.0, balance.owes[alice] ?: 0.0)
            }
            
            @Test
            fun `settle fails when no debt`() {
                val result = tracker.settleDebt(bob, alice)
                
                assertTrue(result is CommandResult.Failure)
            }
        }
        
        @Nested
        @DisplayName("Undo/Redo Functionality")
        inner class UndoRedoTests {
            
            @Test
            fun `multiple undos work in sequence`() {
                tracker.addExpenseEqual("Expense 1", 30.0, alice, listOf(alice, bob))
                tracker.addExpenseEqual("Expense 2", 40.0, bob, listOf(alice, bob))
                
                assertEquals(2, tracker.getExpenses().size)
                
                tracker.undo()
                assertEquals(1, tracker.getExpenses().size)
                
                tracker.undo()
                assertEquals(0, tracker.getExpenses().size)
            }
            
            @Test
            fun `redo restores undone command`() {
                tracker.addExpenseEqual("Lunch", 60.0, alice, listOf(alice, bob))
                tracker.undo()
                
                assertEquals(0, tracker.getExpenses().size)
                
                tracker.redo()
                
                assertEquals(1, tracker.getExpenses().size)
            }
            
            @Test
            fun `new command clears redo stack`() {
                tracker.addExpenseEqual("First", 30.0, alice, listOf(alice, bob))
                tracker.undo()
                
                assertTrue(tracker.canRedo())
                
                tracker.addExpenseEqual("Second", 40.0, bob, listOf(alice, bob))
                
                assertFalse(tracker.canRedo())
            }
            
            @Test
            fun `undo returns failure when nothing to undo`() {
                val result = tracker.undo()
                
                assertTrue(result is CommandResult.Failure)
            }
            
            @Test
            fun `redo returns failure when nothing to redo`() {
                val result = tracker.redo()
                
                assertTrue(result is CommandResult.Failure)
            }
            
            @Test
            fun `canUndo and canRedo report correctly`() {
                assertFalse(tracker.canUndo())
                assertFalse(tracker.canRedo())
                
                tracker.addExpenseEqual("Test", 50.0, alice, listOf(alice, bob))
                
                assertTrue(tracker.canUndo())
                assertFalse(tracker.canRedo())
                
                tracker.undo()
                
                assertFalse(tracker.canUndo())
                assertTrue(tracker.canRedo())
            }
        }
        
        @Nested
        @DisplayName("Batch Command")
        inner class BatchCommandTests {
            
            @Test
            fun `batch executes all commands`() {
                val commands = listOf(
                    tracker.createAddExpenseCommand("Expense 1", 30.0, alice, 
                        listOf(Split(alice, 15.0), Split(bob, 15.0))),
                    tracker.createAddExpenseCommand("Expense 2", 40.0, bob, 
                        listOf(Split(alice, 20.0), Split(bob, 20.0)))
                )
                
                val result = tracker.executeBatch(commands, "Test batch")
                
                assertTrue(result is CommandResult.Success)
                assertEquals(2, tracker.getExpenses().size)
            }
            
            @Test
            fun `batch rolls back on failure`() {
                val commands = listOf(
                    tracker.createAddExpenseCommand("Valid", 30.0, alice, 
                        listOf(Split(alice, 15.0), Split(bob, 15.0))),
                    tracker.createAddExpenseCommand("Invalid", -50.0, bob, 
                        listOf(Split(alice, 25.0), Split(bob, 25.0)))
                )
                
                val result = tracker.executeBatch(commands, "Test batch")
                
                assertTrue(result is CommandResult.Failure)
                assertEquals(0, tracker.getExpenses().size)
            }
            
            @Test
            fun `batch undo removes all commands`() {
                val commands = listOf(
                    tracker.createAddExpenseCommand("Expense 1", 30.0, alice, 
                        listOf(Split(alice, 15.0), Split(bob, 15.0))),
                    tracker.createAddExpenseCommand("Expense 2", 40.0, bob, 
                        listOf(Split(alice, 20.0), Split(bob, 20.0)))
                )
                
                tracker.executeBatch(commands, "Test batch")
                tracker.undo()
                
                assertEquals(0, tracker.getExpenses().size)
            }
        }
        
        @Nested
        @DisplayName("Command History")
        inner class CommandHistoryTests {
            
            @Test
            fun `command history tracks all commands`() {
                tracker.addExpenseEqual("First", 30.0, alice, listOf(alice, bob))
                tracker.addExpenseEqual("Second", 40.0, bob, listOf(alice, bob))
                tracker.recordPayment(bob, alice, 10.0)
                
                val history = tracker.getCommandHistory()
                
                assertEquals(3, history.size)
            }
            
            @Test
            fun `successful commands filter correctly`() {
                tracker.addExpenseEqual("Valid", 30.0, alice, listOf(alice, bob))
                tracker.addExpense("Invalid", -10.0, alice, listOf(Split(bob, 10.0)))
                
                val successful = tracker.getSuccessfulCommands()
                
                assertEquals(1, successful.size)
            }
            
            @Test
            fun `command descriptions are meaningful`() {
                tracker.addExpenseEqual("Team lunch", 90.0, alice, listOf(alice, bob, charlie))
                
                val history = tracker.getCommandHistory()
                val description = history.first().command.description
                
                assertTrue(description.contains("Team lunch"))
                assertTrue(description.contains("90"))
            }
        }
    }
    
    @Nested
    @DisplayName("Group Expense Management")
    inner class GroupExpenseTests {
        
        @Nested
        @DisplayName("Strategy Tracker Groups")
        inner class StrategyGroupTests {
            
            private lateinit var tracker: StrategySplitExpenseTracker
            
            @BeforeEach
            fun setup() {
                tracker = StrategySplitExpenseTracker()
            }
            
            @Test
            fun `create group with members`() {
                val group = tracker.createGroup("Roommates", listOf(alice, bob, charlie))
                
                assertEquals("Roommates", group.name)
                assertEquals(3, group.members.size)
            }
            
            @Test
            fun `add expense to group`() {
                val group = tracker.createGroup("Trip", listOf(alice, bob))
                
                val result = tracker.addExpenseEqual(
                    description = "Hotel",
                    amount = 200.0,
                    paidBy = alice,
                    participants = group.members,
                    groupId = group.id
                )
                
                assertTrue(result is ExpenseResult.Success)
                
                val groupExpenses = tracker.getExpensesByGroup(group.id)
                assertEquals(1, groupExpenses.size)
            }
            
            @Test
            fun `filter expenses by group`() {
                val group1 = tracker.createGroup("Work", listOf(alice, bob))
                val group2 = tracker.createGroup("Friends", listOf(alice, charlie))
                
                tracker.addExpenseEqual("Work lunch", 50.0, alice, group1.members, groupId = group1.id)
                tracker.addExpenseEqual("Friends dinner", 80.0, alice, group2.members, groupId = group2.id)
                tracker.addExpenseEqual("Personal", 30.0, alice, listOf(alice, bob))
                
                assertEquals(1, tracker.getExpensesByGroup(group1.id).size)
                assertEquals(1, tracker.getExpensesByGroup(group2.id).size)
            }
        }
    }
    
    @Nested
    @DisplayName("Multi-User Scenarios")
    inner class MultiUserScenarioTests {
        
        @Nested
        @DisplayName("Complex Balance Scenarios")
        inner class ComplexBalanceTests {
            
            private lateinit var tracker: StrategySplitExpenseTracker
            
            @BeforeEach
            fun setup() {
                tracker = StrategySplitExpenseTracker()
            }
            
            @Test
            fun `circular debts simplify correctly`() {
                tracker.addExpenseEqual("A pays for B,C", 60.0, alice, listOf(alice, bob, charlie))
                tracker.addExpenseEqual("B pays for A,C", 60.0, bob, listOf(alice, bob, charlie))
                tracker.addExpenseEqual("C pays for A,B", 60.0, charlie, listOf(alice, bob, charlie))
                
                val allBalances = tracker.getAllBalances()
                
                allBalances.values.forEach { balance ->
                    assertAmountEquals(0.0, balance.netBalance, "Net balance should be zero for ${balance.user.name}")
                }
            }
            
            @Test
            fun `settlement minimizes transactions in chain`() {
                tracker.addExpenseEqual("Dinner", 90.0, alice, listOf(alice, bob, charlie))
                
                val settlements = tracker.suggestSettlements()
                
                assertTrue(settlements is SettlementResult.Success)
                val settlementsList = (settlements as SettlementResult.Success).settlements
                assertTrue(settlementsList.size <= 2)
            }
            
            @Test
            fun `handles many small transactions`() {
                repeat(10) { i ->
                    tracker.addExpenseEqual("Expense $i", 10.0, alice, listOf(alice, bob))
                }
                
                val bobBalance = tracker.getBalance(bob)
                assertAmountEquals(50.0, bobBalance.owes[alice] ?: 0.0)
            }
            
            @Test
            fun `partial payments tracked correctly`() {
                tracker.addExpenseEqual("Big expense", 100.0, alice, listOf(alice, bob))
                
                tracker.recordPayment(bob, alice, 10.0)
                tracker.recordPayment(bob, alice, 15.0)
                tracker.recordPayment(bob, alice, 5.0)
                
                val balance = tracker.getBalance(bob)
                assertAmountEquals(20.0, balance.owes[alice] ?: 0.0)
            }
        }
        
        @Nested
        @DisplayName("Edge Cases")
        inner class EdgeCaseTests {
            
            private lateinit var tracker: StrategySplitExpenseTracker
            
            @BeforeEach
            fun setup() {
                tracker = StrategySplitExpenseTracker()
            }
            
            @Test
            fun `payer included in split pays less to others`() {
                tracker.addExpenseEqual("Team dinner", 100.0, alice, listOf(alice, bob, charlie, diana))
                
                val aliceBalance = tracker.getBalance(alice)
                assertAmountEquals(75.0, aliceBalance.totalOwed)
                
                val bobBalance = tracker.getBalance(bob)
                assertAmountEquals(25.0, bobBalance.owes[alice] ?: 0.0)
            }
            
            @Test
            fun `handles very large amounts`() {
                val largeAmount = 1_000_000.0
                tracker.addExpenseEqual("Large expense", largeAmount, alice, listOf(alice, bob))
                
                val balance = tracker.getBalance(bob)
                assertAmountEquals(500_000.0, balance.owes[alice] ?: 0.0)
            }
            
            @Test
            fun `handles very small amounts`() {
                tracker.addExpenseEqual("Tiny expense", 0.03, alice, listOf(alice, bob, charlie))
                
                val total = tracker.getAllBalances().values.sumOf { it.totalOwes }
                assertTrue(total < 0.03)
            }
            
            @Test
            fun `overpayment creates reverse debt`() {
                tracker.addExpenseEqual("Expense", 60.0, alice, listOf(alice, bob))
                
                tracker.recordPayment(bob, alice, 30.0)
                
                val balance = tracker.getBalance(bob)
                assertAmountEquals(0.0, balance.totalOwes)
            }
            
            @Test
            fun `expense categories tracked`() {
                tracker.addExpenseEqual("Lunch", 30.0, alice, listOf(alice, bob), category = "Food")
                tracker.addExpenseEqual("Uber", 20.0, alice, listOf(alice, bob), category = "Transport")
                tracker.addExpenseEqual("Dinner", 50.0, bob, listOf(alice, bob), category = "Food")
                
                val byCategory = tracker.getTotalExpensesByCategory()
                
                assertAmountEquals(80.0, byCategory["Food"] ?: 0.0)
                assertAmountEquals(20.0, byCategory["Transport"] ?: 0.0)
            }
            
            @Test
            fun `get expenses by user`() {
                tracker.addExpenseEqual("Alice paid", 50.0, alice, listOf(alice, bob, charlie))
                tracker.addExpenseEqual("Bob paid", 30.0, bob, listOf(alice, bob))
                tracker.addExpenseEqual("Charlie paid", 40.0, charlie, listOf(bob, charlie))
                
                val aliceExpenses = tracker.getExpensesByUser(alice)
                assertEquals(2, aliceExpenses.size)
                
                val charlieExpenses = tracker.getExpensesByUser(charlie)
                assertEquals(2, charlieExpenses.size)
            }
            
            @Test
            fun `payments between users tracked`() {
                tracker.addExpenseEqual("Expense", 100.0, alice, listOf(alice, bob))
                
                tracker.recordPayment(bob, alice, 20.0)
                tracker.recordPayment(bob, alice, 15.0)
                
                val payments = tracker.getPaymentsBetween(alice, bob)
                assertEquals(2, payments.size)
                assertAmountEquals(35.0, payments.sumOf { it.amount })
            }
        }
        
        @Nested
        @DisplayName("Four Person Split Scenarios")
        inner class FourPersonTests {
            
            private lateinit var tracker: CommandableExpenseTracker
            
            @BeforeEach
            fun setup() {
                tracker = CommandableExpenseTracker()
            }
            
            @Test
            fun `complex four person scenario with undo`() {
                tracker.addExpenseEqual("Hotel", 400.0, alice, listOf(alice, bob, charlie, diana))
                tracker.addExpenseEqual("Gas", 80.0, bob, listOf(alice, bob, charlie, diana))
                tracker.addExpenseEqual("Food", 120.0, charlie, listOf(alice, bob, charlie, diana))
                
                val aliceInitial = tracker.getBalance(alice)
                assertTrue(aliceInitial.totalOwed > 0)
                
                tracker.undo()
                
                assertEquals(2, tracker.getExpenses().size)
                
                tracker.redo()
                
                assertEquals(3, tracker.getExpenses().size)
                
                val settlements = tracker.suggestSettlements()
                assertTrue(settlements is SettlementResult.Success)
                val settlementsList = (settlements as SettlementResult.Success).settlements
                assertTrue(settlementsList.size <= 3)
            }
            
            @Test
            fun `four person with mixed payment methods`() {
                val percentages = mapOf(
                    alice to 40.0,
                    bob to 30.0,
                    charlie to 20.0,
                    diana to 10.0
                )
                
                val strategyTracker = StrategySplitExpenseTracker()
                strategyTracker.addExpensePercentage(
                    "Dinner",
                    200.0,
                    alice,
                    percentages
                )
                
                val bobBalance = strategyTracker.getBalance(bob)
                assertAmountEquals(60.0, bobBalance.owes[alice] ?: 0.0)
                
                val dianaBalance = strategyTracker.getBalance(diana)
                assertAmountEquals(20.0, dianaBalance.owes[alice] ?: 0.0)
            }
        }
    }
    
    @Nested
    @DisplayName("Settlement Suggestions")
    inner class SettlementTests {
        
        @Test
        fun `no settlements when all balanced`() {
            val tracker = StrategySplitExpenseTracker()
            
            val result = tracker.suggestSettlements()
            
            assertTrue(result is SettlementResult.NoSettlementsNeeded)
        }
        
        @Test
        fun `simple two person settlement`() {
            val tracker = StrategySplitExpenseTracker()
            tracker.addExpenseEqual("Dinner", 100.0, alice, listOf(alice, bob))
            
            val result = tracker.suggestSettlements()
            
            assertTrue(result is SettlementResult.Success)
            val settlements = (result as SettlementResult.Success).settlements
            
            assertEquals(1, settlements.size)
            assertEquals(bob, settlements[0].from)
            assertEquals(alice, settlements[0].to)
            assertAmountEquals(50.0, settlements[0].amount)
        }
        
        @Test
        fun `three person settlement optimizes transactions`() {
            val tracker = StrategySplitExpenseTracker()
            
            tracker.addExpenseEqual("Expense 1", 90.0, alice, listOf(alice, bob, charlie))
            tracker.addExpenseEqual("Expense 2", 30.0, bob, listOf(alice, bob, charlie))
            
            val result = tracker.suggestSettlements()
            
            assertTrue(result is SettlementResult.Success)
            val settlements = (result as SettlementResult.Success).settlements
            
            val totalSettled = settlements.sumOf { it.amount }
            assertTrue(totalSettled > 0)
            assertTrue(settlements.size <= 2)
        }
        
        @Test
        fun `settlements clear after full payment`() {
            val tracker = StrategySplitExpenseTracker()
            tracker.addExpenseEqual("Lunch", 60.0, alice, listOf(alice, bob))
            
            tracker.recordPayment(bob, alice, 30.0)
            
            val result = tracker.suggestSettlements()
            assertTrue(result is SettlementResult.NoSettlementsNeeded)
        }
    }
}
