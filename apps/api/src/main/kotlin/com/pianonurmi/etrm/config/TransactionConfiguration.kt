package com.pianonurmi.etrm.config

import com.pianonurmi.etrm.platform.security.ActorProvider
import io.micrometer.tracing.Tracer
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizers
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.TransactionDefinition
import java.sql.Connection
import javax.sql.DataSource

/**
 * Tells the database who acts, and in which request, for every transaction that may write (review finding S6):
 * `etrm.actor` is read by the history triggers (`reference_history`, `registry_instruction_event`) and
 * `etrm.correlation` (the trace id) is stored with audit records that carry no correlation id of their own. Both
 * settings are local to the transaction, so a pooled connection never carries them into the next one.
 */
class SessionContextTransactionManager(
    dataSource: DataSource,
    private val actors: ActorProvider,
    private val tracer: ObjectProvider<Tracer>,
) : JdbcTransactionManager(dataSource) {
    override fun prepareTransactionalConnection(con: Connection, definition: TransactionDefinition) {
        super.prepareTransactionalConnection(con, definition)
        if (definition.isReadOnly) return
        val traceId = tracer.ifAvailable?.currentSpan()?.context()?.traceId()
        con.prepareStatement("SELECT set_config('etrm.actor', ?, true), set_config('etrm.correlation', ?, true)").use {
            it.setString(1, actors.current().userId)
            it.setString(2, traceId.orEmpty())
            it.execute()
        }
    }
}

/** Replaces Spring Boot's transaction manager (it backs off when one is defined) with [SessionContextTransactionManager]. */
@Configuration
class TransactionConfiguration {
    @Bean
    fun transactionManager(
        dataSource: DataSource,
        actors: ActorProvider,
        tracer: ObjectProvider<Tracer>,
        customizers: ObjectProvider<TransactionManagerCustomizers>,
    ): JdbcTransactionManager = SessionContextTransactionManager(dataSource, actors, tracer).also { manager ->
        customizers.ifAvailable { it.customize(manager) }
    }
}
