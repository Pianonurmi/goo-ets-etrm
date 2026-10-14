package com.pianonurmi.etrm

import com.pianonurmi.etrm.config.LoginThrottle
import com.pianonurmi.etrm.config.ShowcaseProperties
import com.pianonurmi.etrm.config.ShowcaseSecurityConfiguration
import com.pianonurmi.etrm.config.ShowcaseUsers
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class ShowcaseUsersTest {
    private val encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()

    @Test
    fun `users come from the list and the single password, hashed`() {
        val users = ShowcaseUsers.parse(
            ShowcaseProperties(users = "visitor:correct-horse-battery, guest:another-long-one", password = "demo-password-123"),
            encoder,
        )
        users.map { it.username } shouldBe listOf("visitor", "guest", "demo")
        users.first().password shouldStartWith "{bcrypt}"
        encoder.matches("correct-horse-battery", users.first().password) shouldBe true
    }

    @Test
    fun `a pre-hashed password is taken as it is`() {
        val hash = ShowcaseSecurityConfiguration.bcrypt("short")
        ShowcaseUsers.parse(ShowcaseProperties(users = "visitor:$hash"), encoder).single().password shouldBe hash
    }

    @Test
    fun `no users, short, guessable or duplicate entries refuse to start`() {
        shouldThrow<IllegalArgumentException> { ShowcaseUsers.parse(ShowcaseProperties(), encoder) }.message shouldContain "ETRM_DEMO_PASSWORD"
        shouldThrow<IllegalArgumentException> { ShowcaseUsers.parse(ShowcaseProperties(password = "short"), encoder) }
        shouldThrow<IllegalArgumentException> { ShowcaseUsers.parse(ShowcaseProperties(users = "visitorvisitor:VisitorVisitor"), encoder) }
        shouldThrow<IllegalArgumentException> { ShowcaseUsers.parse(ShowcaseProperties(users = "visitor:PASSWORD"), encoder) }
        shouldThrow<IllegalArgumentException> { ShowcaseUsers.parse(ShowcaseProperties(users = "a:long-password-1,a:long-password-2"), encoder) }
        shouldThrow<IllegalArgumentException> { ShowcaseUsers.parse(ShowcaseProperties(users = "no password"), encoder) }
    }

    @Test
    fun `the throttle locks a user or an address after five failures, for the window`() {
        var now = Instant.parse("2026-10-11T10:00:00Z")
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: java.time.ZoneId?) = this

            override fun instant() = now
        }
        val throttle = LoginThrottle(clock, globalPerMinute = 1000)
        repeat(5) {
            throttle.retryAfter("demo", "198.51.100.1").shouldBeNull()
            throttle.failed("demo", "198.51.100.1")
        }
        throttle.retryAfter("demo", "198.51.100.2").shouldNotBeNull()
        throttle.retryAfter("other", "198.51.100.1").shouldNotBeNull()
        throttle.retryAfter("other", "198.51.100.2").shouldBeNull()
        now = now.plus(Duration.ofMinutes(16))
        throttle.retryAfter("demo", "198.51.100.1").shouldBeNull()
    }

    @Test
    fun `the throttle caps failures across all users, but successful sign-ins never lock anyone out`() {
        val throttle = LoginThrottle(globalPerMinute = 3)
        repeat(10) { throttle.retryAfter("ok$it", "203.0.113.50").shouldBeNull() }
        repeat(3) {
            throttle.retryAfter("u$it", "203.0.113.$it").shouldBeNull()
            throttle.failed("u$it", "203.0.113.$it")
        }
        throttle.retryAfter("u9", "203.0.113.9").shouldNotBeNull()
    }
}
