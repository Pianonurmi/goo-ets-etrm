package com.pianonurmi.etrm.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.crypto.password.PasswordEncoder

/**
 * ```yaml
 * etrm.showcase:
 *   users: ${ETRM_DEMO_USERS:}        # "visitor:secret-one,guest:secret-two"; a value may already be {bcrypt}…
 *   password: ${ETRM_DEMO_PASSWORD:}  # shorthand for the single user "demo"
 * ```
 */
@ConfigurationProperties(prefix = "etrm.showcase")
data class ShowcaseProperties(
    val users: String = "",
    val password: String = "",
)

/** The demo sign-ins of the showcase (ADR 0032): parsed and checked at start, passwords kept only as BCrypt hashes. */
object ShowcaseUsers {
    const val MIN_PASSWORD_LENGTH = 12
    private val WEAK = setOf("demo", "password", "changeme", "admin", "secret")
    private val USERNAME = Regex("[A-Za-z0-9._@-]{1,64}")

    fun parse(properties: ShowcaseProperties, encoder: PasswordEncoder): List<UserDetails> {
        val pairs = buildList {
            properties.users.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { entry ->
                val separator = entry.indexOf(':')
                require(separator > 0) { "etrm.showcase.users: expected user:password, got an entry without ':'" }
                add(entry.substring(0, separator).trim() to entry.substring(separator + 1))
            }
            if (properties.password.isNotEmpty()) add("demo" to properties.password)
        }
        require(pairs.isNotEmpty()) {
            "The showcase profile needs at least one demo user: set ETRM_DEMO_PASSWORD (user 'demo') or ETRM_DEMO_USERS (user:password,…)"
        }
        require(pairs.map { it.first }.toSet().size == pairs.size) { "etrm.showcase.users lists a user twice" }
        return pairs.map { (username, password) ->
            require(USERNAME.matches(username)) { "Demo user name '$username' may use letters, digits and . _ @ - only" }
            val hash = if (password.startsWith("{bcrypt}")) {
                password
            } else {
                check(username, password)
                encoder.encode(password)
            }
            User.withUsername(username).password(hash).roles("VISITOR").build()
        }
    }

    private fun check(username: String, password: String) {
        require(password.length >= MIN_PASSWORD_LENGTH) {
            "The password of demo user '$username' is shorter than $MIN_PASSWORD_LENGTH characters"
        }
        require(!password.equals(username, ignoreCase = true) && password.lowercase() !in WEAK) {
            "The password of demo user '$username' is too easy to guess"
        }
    }
}
