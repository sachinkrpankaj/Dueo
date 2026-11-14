package com.example.dueo

import com.example.dueo.data.*
import org.junit.Assert.*
import org.junit.Test

class FinancialValidatorTest {

    private val personAlex = Person(id = "p-alex", name = "Alex", phone = "1234567890")
    private val personRahul = Person(id = "p-rahul", name = "Rahul", phone = "9876543210")

    @Test
    fun strictlyPositiveAmountsRequired() {
        assertThrows(InvalidAmountException::class.java) {
            FinancialValidator.validateEntryBasic(
                Entry(id = "e1", person = "Alex", amount = 0, kind = EntryKind.LENT, personId = "p-alex")
            )
        }
        assertThrows(InvalidAmountException::class.java) {
            FinancialValidator.validateEntryBasic(
                Entry(id = "e2", person = "Alex", amount = -500, kind = EntryKind.LENT, personId = "p-alex")
            )
        }
    }

    @Test
    fun independentReceivableAndPayableObligationsPreserved() {
        // Example from Section 3:
        // User lends Rahul ₹2,000 (200000 paise).
        // Rahul returns ₹500 (50000 paise).
        // User separately borrows ₹300 (30000 paise) from Rahul.
        // Balances must be: receive = ₹1,500 (150000 paise), pay = ₹300 (30000 paise).
        val entries = listOf(
            Entry(id = "e1", person = "Rahul", personId = "p-rahul", amount = 200000L, kind = EntryKind.LENT, date = 1000),
            Entry(id = "e2", person = "Rahul", personId = "p-rahul", amount = 50000L, kind = EntryKind.RECEIVED, date = 2000),
            Entry(id = "e3", person = "Rahul", personId = "p-rahul", amount = 30000L, kind = EntryKind.BORROWED, date = 3000)
        )
        val ledger = LedgerState(people = listOf(personRahul), entries = entries)
        FinancialValidator.validateLedger(ledger)

        val balance = entries.balance()
        assertEquals(150000L, balance.receive)
        assertEquals(30000L, balance.pay)
    }

    @Test
    fun repaymentsCannotExceedOutstandingObligation() {
        // Attempting to receive ₹600 repayment on ₹500 loan must throw OverRepaymentException
        val entries = listOf(
            Entry(id = "e1", person = "Alex", personId = "p-alex", amount = 50000L, kind = EntryKind.LENT, date = 1000),
            Entry(id = "e2", person = "Alex", personId = "p-alex", amount = 60000L, kind = EntryKind.RECEIVED, date = 2000)
        )
        val ledger = LedgerState(people = listOf(personAlex), entries = entries)
        val ex = assertThrows(OverRepaymentException::class.java) {
            FinancialValidator.validateLedger(ledger)
        }
        assertTrue(ex.message!!.contains("exceeds outstanding obligation"))
    }

    @Test
    fun repaymentsPaidCannotExceedOutstandingBorrowing() {
        // Attempting to pay ₹400 repayment when user only borrowed ₹300
        val entries = listOf(
            Entry(id = "e1", person = "Alex", personId = "p-alex", amount = 30000L, kind = EntryKind.BORROWED, date = 1000),
            Entry(id = "e2", person = "Alex", personId = "p-alex", amount = 40000L, kind = EntryKind.PAID, date = 2000)
        )
        val ledger = LedgerState(people = listOf(personAlex), entries = entries)
        assertThrows(OverRepaymentException::class.java) {
            FinancialValidator.validateLedger(ledger)
        }
    }

    @Test
    fun chronologicalAccountingPreventsPrematureRepayment() {
        // Loan occurs at date 2000, but repayment is dated at 1000 (before loan was made!)
        val entries = listOf(
            Entry(id = "e1", person = "Alex", personId = "p-alex", amount = 100000L, kind = EntryKind.LENT, date = 2000),
            Entry(id = "e2", person = "Alex", personId = "p-alex", amount = 50000L, kind = EntryKind.RECEIVED, date = 1000)
        )
        val ledger = LedgerState(people = listOf(personAlex), entries = entries)
        val ex = assertThrows(OverRepaymentException::class.java) {
            FinancialValidator.validateLedger(ledger)
        }
        assertTrue(ex.message!!.contains("exceeds outstanding obligation"))
    }

    @Test
    fun duplicatePersonIdRejected() {
        val ledger = LedgerState(
            people = listOf(
                Person(id = "dup-id", name = "Alex"),
                Person(id = "dup-id", name = "Bob")
            )
        )
        assertThrows(DuplicatePersonIdException::class.java) {
            FinancialValidator.validateLedger(ledger)
        }
    }

    @Test
    fun duplicateTransactionIdRejected() {
        val ledger = LedgerState(
            people = listOf(personAlex),
            entries = listOf(
                Entry(id = "same-tx-id", person = "Alex", personId = "p-alex", amount = 1000, kind = EntryKind.LENT),
                Entry(id = "same-tx-id", person = "Alex", personId = "p-alex", amount = 2000, kind = EntryKind.LENT)
            )
        )
        assertThrows(DuplicateTransactionIdException::class.java) {
            FinancialValidator.validateLedger(ledger)
        }
    }

    @Test
    fun nonexistentPersonReferencedInEntryRejected() {
        val ledger = LedgerState(
            people = listOf(personAlex),
            entries = listOf(
                Entry(id = "e1", person = "Unknown", personId = "ghost-id", amount = 1000, kind = EntryKind.LENT)
            )
        )
        assertThrows(PersonNotFoundException::class.java) {
            FinancialValidator.validateLedger(ledger)
        }
    }

    @Test
    fun blankOrWhitespacePersonNameRejected() {
        assertThrows(BlankPersonNameException::class.java) {
            FinancialValidator.validatePerson(Person(id = "p1", name = "   "))
        }
        assertThrows(BlankPersonNameException::class.java) {
            FinancialValidator.validatePerson(Person(id = "p1", name = ""))
        }
    }

    @Test
    fun arithmeticOverflowHandledSafely() {
        assertThrows(FinancialOverflowException::class.java) {
            FinancialValidator.safeAdd(Long.MAX_VALUE, 1L)
        }
        assertThrows(FinancialOverflowException::class.java) {
            FinancialValidator.safeSubtract(Long.MIN_VALUE, 1L)
        }
    }

    @Test
    fun multiplePeopleWithMixedObligationsRemainSeparated() {
        val entries = listOf(
            Entry(id = "e1", person = "Alex", personId = "p-alex", amount = 100000L, kind = EntryKind.LENT, date = 1000),
            Entry(id = "e2", person = "Rahul", personId = "p-rahul", amount = 50000L, kind = EntryKind.BORROWED, date = 1100),
            Entry(id = "e3", person = "Alex", personId = "p-alex", amount = 20000L, kind = EntryKind.RECEIVED, date = 1200),
            Entry(id = "e4", person = "Rahul", personId = "p-rahul", amount = 10000L, kind = EntryKind.PAID, date = 1300)
        )
        val ledger = LedgerState(people = listOf(personAlex, personRahul), entries = entries)
        FinancialValidator.validateLedger(ledger)

        val alexEntries = entries.filter { it.personId == "p-alex" }
        val rahulEntries = entries.filter { it.personId == "p-rahul" }

        assertEquals(80000L, alexEntries.balance().receive)
        assertEquals(0L, alexEntries.balance().pay)

        assertEquals(0L, rahulEntries.balance().receive)
        assertEquals(40000L, rahulEntries.balance().pay)

        val total = entries.balance()
        assertEquals(80000L, total.receive)
        assertEquals(40000L, total.pay)
    }

    @Test
    fun dueDateSupportedAndPreservedInEntry() {
        val due = 1750000000000L
        val entry = Entry(
            id = "e1",
            person = "Alex",
            personId = "p-alex",
            amount = 10000,
            kind = EntryKind.LENT,
            dueDate = due
        )
        assertEquals(due, entry.dueDate)
    }
}
