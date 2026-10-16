package com.pianonurmi.etrm.platform.security

/**
 * The actor of work that left its request (an async run, `platform.async`): the request's actor is bound for the
 * block, and the application's [ActorProvider] answers it before looking at the security context, which a worker
 * thread does not have. Scoped, so the binding ends with the block and never leaks into pooled threads.
 */
object ActorBinding {
    private val BOUND: ScopedValue<Actor> = ScopedValue.newInstance()

    fun bound(): Actor? = if (BOUND.isBound) BOUND.get() else null

    fun <T> runAs(actor: Actor, block: () -> T): T = ScopedValue.where(BOUND, actor).call<T, RuntimeException> { block() }
}
