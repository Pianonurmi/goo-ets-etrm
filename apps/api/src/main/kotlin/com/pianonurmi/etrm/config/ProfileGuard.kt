package com.pianonurmi.etrm.config

import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.net.InetAddress

/**
 * Refuses to start with the `dev` profile where it could be reached from outside. That profile authenticates
 * nobody: anyone who can reach the server acts as any persona, by default as ADMIN. It must therefore listen on a
 * loopback address only (`application-dev.yml` binds 127.0.0.1), never run with the public profiles, and opt in
 * explicitly when it runs in a container behind a published port (`etrm.dev.allow-remote`, local compose only).
 *
 * A bean, not an environment post-processor: it fails during context refresh, before the web server opens its port.
 */
@Component
class ProfileGuard(environment: Environment) {
    init {
        check(environment)
    }

    companion object {
        private val PUBLIC_PROFILES = listOf("showcase", "kubernetes")

        fun check(environment: Environment) {
            if (!environment.matchesProfiles("dev")) return
            PUBLIC_PROFILES.filter { environment.matchesProfiles(it) }.forEach {
                throw IllegalStateException("The dev profile (no authentication) cannot run together with the '$it' profile")
            }
            val allowRemote = environment.getProperty("etrm.dev.allow-remote", Boolean::class.java, false)
            val address = environment.getProperty("server.address")
            if (!allowRemote && !isLoopback(address)) {
                throw IllegalStateException(
                    "The dev profile (no authentication) must listen on a loopback address, not '${address ?: "all interfaces"}'. " +
                        "Set server.address=127.0.0.1, or etrm.dev.allow-remote=true for a local container only.",
                )
            }
        }

        private fun isLoopback(address: String?): Boolean {
            if (address.isNullOrBlank()) return false
            return runCatching { InetAddress.getByName(address).isLoopbackAddress }.getOrDefault(false)
        }
    }
}
