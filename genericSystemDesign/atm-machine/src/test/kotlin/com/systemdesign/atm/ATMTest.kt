package com.systemdesign.atm

import com.systemdesign.atm.common.*
import com.systemdesign.atm.approach_01_state_machine.StateMachineATM
import com.systemdesign.atm.approach_02_chain_responsibility.*
import com.systemdesign.atm.approach_03_template_method.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested

class ATMTest {
    private val testCard = Card("1234567890", "12/25", "BANK001")
    private val testAccount = Account("ACC001", 1000.0, "1234")
    
    private val mockBankingService = object : BankingService {
        override fun validateCard(card: Card) = card.cardNumber == testCard.cardNumber
        override fun validatePin(cardNumber: String, pin: String) = 
            if (pin == testAccount.pin) testAccount else null
        override fun withdraw(account: Account, amount: Double) = account.balance >= amount
        override fun deposit(account: Account, amount: Double) = true
        override fun getBalance(account: Account) = account.balance
        override fun transfer(from: Account, toAccountNumber: String, amount: Double) = from.balance >= amount
    }
    
    @Nested
    inner class StateMachineATMTest {
        private lateinit var atm: StateMachineATM
        
        @BeforeEach
        fun setup() {
            atm = StateMachineATM(
                mockBankingService,
                listOf(CashCassette(100, 10), CashCassette(50, 20), CashCassette(20, 30))
            )
        }
        
        @Test
        fun `starts in idle state`() {
            assertEquals(SessionState.IDLE, atm.getState().sessionState)
            assertEquals(CardState.NONE, atm.getState().cardState)
        }
        
        @Test
        fun `insert card transitions to card_inserted`() {
            assertTrue(atm.insertCard(testCard))
            assertEquals(SessionState.CARD_INSERTED, atm.getState().sessionState)
        }
        
        @Test
        fun `correct PIN transitions to pin_entered`() {
            atm.insertCard(testCard)
            assertTrue(atm.enterPin("1234"))
            assertEquals(SessionState.PIN_ENTERED, atm.getState().sessionState)
        }
        
        @Test
        fun `wrong PIN increments attempts`() {
            atm.insertCard(testCard)
            assertFalse(atm.enterPin("0000"))
            assertEquals(1, atm.getState().pinAttempts)
        }
        
        @Test
        fun `card swallowed after 3 wrong PINs`() {
            atm.insertCard(testCard)
            repeat(3) { atm.enterPin("0000") }
            assertEquals(CardState.SWALLOWED, atm.getState().cardState)
        }
        
        @Test
        fun `withdrawal succeeds with sufficient balance`() {
            atm.insertCard(testCard)
            atm.enterPin("1234")
            atm.selectTransaction(TransactionType.WITHDRAWAL, 200.0)
            val result = atm.processTransaction()
            assertEquals(TransactionStatus.SUCCESS, result?.status)
        }
        
        @Test
        fun `cancel resets state`() {
            atm.insertCard(testCard)
            atm.enterPin("1234")
            atm.cancel()
            assertEquals(SessionState.IDLE, atm.getState().sessionState)
        }
    }
    
    @Nested
    inner class ChainDenominationTest {
        @Test
        fun `dispenses exact amount`() {
            val dispenser = ChainBasedDispenser(listOf(
                CashCassette(100, 10), CashCassette(50, 10), CashCassette(20, 10)
            ))
            val result = dispenser.dispense(270)
            assertEquals(0, result.remaining)
            assertEquals(2, result.dispensed[100])
            assertEquals(1, result.dispensed[50])
            assertEquals(1, result.dispensed[20])
        }
        
        @Test
        fun `handles insufficient denominations`() {
            val dispenser = ChainBasedDispenser(listOf(CashCassette(100, 1)))
            val result = dispenser.dispense(150)
            assertEquals(50, result.remaining)
        }
        
        @Test
        fun `canDispense returns false for impossible amounts`() {
            val dispenser = ChainBasedDispenser(listOf(CashCassette(100, 1)))
            assertFalse(dispenser.canDispense(150))
        }
    }
    
    @Nested
    inner class TemplateMethodTest {
        private val cassettes = listOf(CashCassette(100, 10), CashCassette(50, 20), CashCassette(20, 30))
        private lateinit var processor: ATMTransactionProcessor
        
        @BeforeEach
        fun setup() {
            processor = ATMTransactionProcessor(mockBankingService, cassettes)
        }
        
        @Test
        fun `withdrawal succeeds with valid credentials`() {
            val result = processor.processTransaction(
                TransactionType.WITHDRAWAL, testCard, "1234", amount = 200.0
            )
            assertTrue(result is TransactionResult.Success)
            val receipt = (result as TransactionResult.Success).receipt
            assertEquals(TransactionType.WITHDRAWAL, receipt.type)
            assertEquals(TransactionStatus.SUCCESS, receipt.status)
        }
        
        @Test
        fun `withdrawal fails with wrong PIN`() {
            val result = processor.processTransaction(
                TransactionType.WITHDRAWAL, testCard, "0000", amount = 200.0
            )
            assertTrue(result is TransactionResult.Failure)
            assertEquals("Authentication failed", (result as TransactionResult.Failure).reason)
        }
        
        @Test
        fun `deposit requires envelope ID`() {
            val result = processor.processTransaction(
                TransactionType.DEPOSIT, testCard, "1234", amount = 500.0
            )
            assertTrue(result is TransactionResult.Failure)
        }
        
        @Test
        fun `deposit succeeds with envelope`() {
            val result = processor.processTransaction(
                TransactionType.DEPOSIT, testCard, "1234",
                amount = 500.0, depositEnvelopeId = "ENV-001"
            )
            assertTrue(result is TransactionResult.Success)
        }
        
        @Test
        fun `balance inquiry always succeeds`() {
            val result = processor.processTransaction(
                TransactionType.BALANCE_INQUIRY, testCard, "1234"
            )
            assertTrue(result is TransactionResult.Success)
        }
        
        @Test
        fun `transfer requires target account`() {
            val result = processor.processTransaction(
                TransactionType.TRANSFER, testCard, "1234", amount = 100.0
            )
            assertTrue(result is TransactionResult.Failure)
        }
        
        @Test
        fun `transaction log records all transactions`() {
            processor.processTransaction(TransactionType.BALANCE_INQUIRY, testCard, "1234")
            processor.processTransaction(TransactionType.WITHDRAWAL, testCard, "1234", amount = 100.0)
            assertEquals(2, processor.getTransactionLog().size)
        }
    }
}
