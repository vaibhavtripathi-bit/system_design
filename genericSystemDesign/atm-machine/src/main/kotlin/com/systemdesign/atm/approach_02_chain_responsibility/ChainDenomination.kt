package com.systemdesign.atm.approach_02_chain_responsibility

import com.systemdesign.atm.common.CashCassette

data class DispenseResult(val dispensed: Map<Int, Int>, val remaining: Int)

abstract class DenominationHandler(protected val cassette: CashCassette) {
    protected var next: DenominationHandler? = null
    
    fun setNext(handler: DenominationHandler): DenominationHandler {
        next = handler
        return handler
    }
    
    fun dispense(amount: Int): DispenseResult {
        val result = doDispense(amount)
        return if (result.remaining > 0 && next != null) {
            val nextResult = next!!.dispense(result.remaining)
            DispenseResult(result.dispensed + nextResult.dispensed, nextResult.remaining)
        } else result
    }
    
    protected abstract fun doDispense(amount: Int): DispenseResult
}

class StandardDenominationHandler(cassette: CashCassette) : DenominationHandler(cassette) {
    override fun doDispense(amount: Int): DispenseResult {
        val needed = amount / cassette.denomination
        val available = minOf(needed, cassette.count)
        cassette.count -= available
        val dispensedAmount = available * cassette.denomination
        return DispenseResult(
            dispensed = if (available > 0) mapOf(cassette.denomination to available) else emptyMap(),
            remaining = amount - dispensedAmount
        )
    }
}

class DenominationChainBuilder {
    private var head: DenominationHandler? = null
    private var tail: DenominationHandler? = null
    
    fun add(cassette: CashCassette): DenominationChainBuilder {
        val handler = StandardDenominationHandler(cassette)
        if (head == null) { head = handler; tail = handler }
        else { tail?.setNext(handler); tail = handler }
        return this
    }
    
    fun build(): DenominationHandler = head ?: throw IllegalStateException("Chain is empty")
}

class ChainBasedDispenser(cassettes: List<CashCassette>) {
    private val sortedCassettes = cassettes.sortedByDescending { it.denomination }
    
    fun dispense(amount: Int): DispenseResult {
        val chain = DenominationChainBuilder().apply {
            sortedCassettes.forEach { add(it) }
        }.build()
        return chain.dispense(amount)
    }
    
    fun canDispense(amount: Int): Boolean {
        val testCassettes = sortedCassettes.map { it.copy() }
        var remaining = amount
        for (cassette in testCassettes) {
            val canUse = (remaining / cassette.denomination).coerceAtMost(cassette.count)
            remaining -= canUse * cassette.denomination
        }
        return remaining == 0
    }
}
