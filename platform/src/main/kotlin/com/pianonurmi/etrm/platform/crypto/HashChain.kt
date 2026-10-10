package com.pianonurmi.etrm.platform.crypto

import java.security.MessageDigest

/**
 * SHA-256 hash chaining for append-only journals. Each entry stores `hash = sha256(previousHash || payload)`,
 * so any retroactive edit breaks the chain and is detectable by a verification sweep.
 */
object HashChain {
    const val GENESIS: String = "0000000000000000000000000000000000000000000000000000000000000000"

    fun next(previousHash: String?, payload: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update((previousHash ?: GENESIS).toByteArray(Charsets.UTF_8))
        digest.update(payload.toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
