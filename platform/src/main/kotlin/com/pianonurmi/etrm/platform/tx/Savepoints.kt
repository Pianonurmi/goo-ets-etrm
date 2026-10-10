package com.pianonurmi.etrm.platform.tx

import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/**
 * Runs a block that may fail without poisoning the surrounding transaction.
 *
 * Calling a `@Transactional` service that throws marks the caller's transaction rollback-only, even when the
 * caller catches the exception. Wrapping the call in [attempt] runs it inside a savepoint: on failure only the
 * savepoint is rolled back and the rollback-only mark is cleared, so the caller can record the failure and commit.
 */
interface Savepoints {
    fun <T> attempt(block: () -> T): T

    companion object {
        /** For code wired without a transaction manager (plain unit tests): runs the block directly. */
        val NONE: Savepoints = object : Savepoints {
            override fun <T> attempt(block: () -> T): T = block()
        }
    }
}

@Component
class SpringSavepoints(transactionManager: PlatformTransactionManager) : Savepoints {
    private val nested = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_NESTED
    }

    override fun <T> attempt(block: () -> T): T = nested.execute { block() }
}
