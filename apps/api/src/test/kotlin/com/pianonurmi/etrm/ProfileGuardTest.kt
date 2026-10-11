package com.pianonurmi.etrm

import com.pianonurmi.etrm.config.ProfileGuard
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

class ProfileGuardTest {
    private fun env(vararg profiles: String, address: String? = null, allowRemote: Boolean? = null) = MockEnvironment().apply {
        setActiveProfiles(*profiles)
        address?.let { setProperty("server.address", it) }
        allowRemote?.let { setProperty("etrm.dev.allow-remote", it.toString()) }
    }

    @Test
    fun `dev on loopback starts`() {
        shouldNotThrowAny { ProfileGuard.check(env("dev", "demo", address = "127.0.0.1")) }
        shouldNotThrowAny { ProfileGuard.check(env("dev", address = "::1")) }
    }

    @Test
    fun `dev on all interfaces or a public address is refused unless allowed for a local container`() {
        shouldThrow<IllegalStateException> { ProfileGuard.check(env("dev")) }.message shouldContain "all interfaces"
        shouldThrow<IllegalStateException> { ProfileGuard.check(env("dev", address = "0.0.0.0")) }
        shouldNotThrowAny { ProfileGuard.check(env("dev", address = "0.0.0.0", allowRemote = true)) }
    }

    @Test
    fun `dev never runs with the public profiles, even when remote access is allowed`() {
        shouldThrow<IllegalStateException> { ProfileGuard.check(env("dev", "showcase", address = "127.0.0.1")) }
            .message shouldContain "showcase"
        shouldThrow<IllegalStateException> { ProfileGuard.check(env("dev", "kubernetes", address = "127.0.0.1", allowRemote = true)) }
    }

    @Test
    fun `other profiles are not affected`() {
        shouldNotThrowAny { ProfileGuard.check(env()) }
        shouldNotThrowAny { ProfileGuard.check(env("test")) }
        shouldNotThrowAny { ProfileGuard.check(env("kubernetes", "json")) }
    }
}
