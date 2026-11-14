package com.example.dueo.data

/**
 * Centralized financial domain exceptions for Dueo.
 * Explicit domain errors enforce invariants across creation, editing, importing, and restoration.
 */
sealed class FinancialValidationException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

class InvalidAmountException(
    val amount: Long,
    message: String = "Transaction amount must be strictly positive (got $amount paise)"
) : FinancialValidationException(message)

class BlankPersonNameException(
    message: String = "Person name cannot be blank"
) : FinancialValidationException(message)

class DuplicatePersonIdException(
    val personId: String,
    message: String = "Duplicate person ID: $personId"
) : FinancialValidationException(message)

class DuplicateTransactionIdException(
    val entryId: String,
    message: String = "Duplicate transaction ID: $entryId"
) : FinancialValidationException(message)

class PersonNotFoundException(
    val personId: String,
    message: String = "Referenced person not found: $personId"
) : FinancialValidationException(message)

class OverRepaymentException(
    val kind: EntryKind,
    val attemptedPaise: Long,
    val maxAllowedPaise: Long,
    val personName: String,
    message: String = "Repayment of ${money(attemptedPaise)} exceeds outstanding obligation of ${money(maxAllowedPaise)} for $personName ($kind)"
) : FinancialValidationException(message)

class FinancialOverflowException(
    val operation: String,
    val a: Long,
    val b: Long,
    message: String = "Financial arithmetic overflow during $operation on $a and $b"
) : FinancialValidationException(message)

class CorruptedLedgerException(
    message: String,
    cause: Throwable? = null
) : FinancialValidationException(message, cause)

/**
 * Authoritative financial validation layer.
 * All transaction creation, editing, importing, and persistence MUST use this layer.
 */
object FinancialValidator {

    fun safeAdd(a: Long, b: Long): Long {
        return try {
            Math.addExact(a, b)
        } catch (e: ArithmeticException) {
            throw FinancialOverflowException("add", a, b)
        }
    }

    fun safeSubtract(a: Long, b: Long): Long {
        return try {
            Math.subtractExact(a, b)
        } catch (e: ArithmeticException) {
            throw FinancialOverflowException("subtract", a, b)
        }
    }

    /**
     * Validates an individual person record.
     */
    fun validatePerson(person: Person) {
        if (person.id.isBlank()) throw BlankPersonNameException("Person ID cannot be blank")
        if (person.name.trim().isBlank()) throw BlankPersonNameException("Person name cannot be blank")
    }

    /**
     * Validates an individual entry record in isolation.
     */
    fun validateEntryBasic(entry: Entry) {
        if (entry.id.isBlank()) throw DuplicateTransactionIdException("", "Transaction ID cannot be blank")
        if (entry.amount <= 0) throw InvalidAmountException(entry.amount)
        if (entry.person.trim().isBlank()) throw BlankPersonNameException("Transaction person name cannot be blank")
        if (entry.personId.isBlank()) throw PersonNotFoundException(entry.personId)
    }

    /**
     * Calculates the independent balances for a sequence of entries.
     * Guaranteed never to automatically offset receivables and payables.
     */
    fun calculateBalance(entries: List<Entry>): Balance {
        var receive = 0L
        var pay = 0L
        for (e in entries) {
            when (e.kind) {
                EntryKind.LENT -> receive = safeAdd(receive, e.amount)
                EntryKind.RECEIVED -> receive = safeSubtract(receive, e.amount)
                EntryKind.BORROWED -> pay = safeAdd(pay, e.amount)
                EntryKind.PAID -> pay = safeSubtract(pay, e.amount)
            }
        }
        return Balance(receive = receive, pay = pay)
    }

    /**
     * Replays and validates a person's entries chronologically.
     * Enforces that at no point in time does a repayment exceed the earlier obligation.
     */
    fun validatePersonHistory(person: Person, entries: List<Entry>) {
        val chronological = entries.sortedWith(compareBy({ it.date }, { it.id }))
        var runningReceive = 0L
        var runningPay = 0L

        for (e in chronological) {
            if (e.amount <= 0) throw InvalidAmountException(e.amount)
            when (e.kind) {
                EntryKind.LENT -> {
                    runningReceive = safeAdd(runningReceive, e.amount)
                }
                EntryKind.BORROWED -> {
                    runningPay = safeAdd(runningPay, e.amount)
                }
                EntryKind.RECEIVED -> {
                    if (e.amount > runningReceive) {
                        throw OverRepaymentException(
                            kind = e.kind,
                            attemptedPaise = e.amount,
                            maxAllowedPaise = runningReceive,
                            personName = person.name
                        )
                    }
                    runningReceive = safeSubtract(runningReceive, e.amount)
                }
                EntryKind.PAID -> {
                    if (e.amount > runningPay) {
                        throw OverRepaymentException(
                            kind = e.kind,
                            attemptedPaise = e.amount,
                            maxAllowedPaise = runningPay,
                            personName = person.name
                        )
                    }
                    runningPay = safeSubtract(runningPay, e.amount)
                }
            }
        }

        if (runningReceive < 0L || runningPay < 0L) {
            throw OverRepaymentException(
                kind = if (runningReceive < 0L) EntryKind.RECEIVED else EntryKind.PAID,
                attemptedPaise = 0L,
                maxAllowedPaise = 0L,
                personName = person.name,
                message = "Corrupted negative balance detected for ${person.name}: receive=$runningReceive, pay=$runningPay"
            )
        }
    }

    /**
     * Authoritative full ledger validation.
     * Enforces:
     * 1. Unique person IDs
     * 2. Non-blank trimmed person names
     * 3. Unique transaction IDs
     * 4. Strictly positive amounts (> 0)
     * 5. Valid foreign keys (personId references existing Person)
     * 6. Chronological repayment limits (no repayment exceeding obligations)
     * 7. Independent receivable and payable balances
     * 8. Safe arithmetic with overflow protection
     */
    fun validateLedger(state: LedgerState) {
        // 1. Validate People
        val personIds = HashSet<String>(state.people.size)
        for (person in state.people) {
            validatePerson(person)
            if (!personIds.add(person.id)) {
                throw DuplicatePersonIdException(person.id)
            }
        }

        val peopleMap = state.people.associateBy { it.id }

        // 2. Validate Transactions
        val entryIds = HashSet<String>(state.entries.size)
        val entriesByPerson = HashMap<String, MutableList<Entry>>()

        for (entry in state.entries) {
            validateEntryBasic(entry)
            if (!entryIds.add(entry.id)) {
                throw DuplicateTransactionIdException(entry.id)
            }
            val person = peopleMap[entry.personId]
                ?: throw PersonNotFoundException(entry.personId)

            entriesByPerson.getOrPut(person.id) { ArrayList() }.add(entry)
        }

        // 3. Validate chronological integrity for each person
        for (person in state.people) {
            val personEntries = entriesByPerson[person.id] ?: emptyList()
            validatePersonHistory(person, personEntries)
        }
    }

    /**
     * Validates whether a prospective entry can be legally added to an existing ledger.
     */
    fun validateProspectiveEntry(
        currentLedger: LedgerState,
        entry: Entry
    ) {
        validateEntryBasic(entry)
        val person = currentLedger.people.firstOrNull { it.id == entry.personId }
            ?: throw PersonNotFoundException(entry.personId)

        if (currentLedger.entries.any { it.id == entry.id }) {
            throw DuplicateTransactionIdException(entry.id)
        }

        val prospectiveEntries = currentLedger.entries + entry
        val prospectiveLedger = currentLedger.copy(entries = prospectiveEntries)
        validateLedger(prospectiveLedger)
    }

    /**
     * Validates whether an updated entry or deletion maintains ledger validity.
     */
    fun validateProspectiveLedger(prospectiveLedger: LedgerState) {
        validateLedger(prospectiveLedger)
    }
}

