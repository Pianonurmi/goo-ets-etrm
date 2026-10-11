package com.pianonurmi.etrm

import com.pianonurmi.etrm.config.SecurityProperties
import com.pianonurmi.etrm.config.SpringSecurityActorProvider
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.Role
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/** Dev persona headers identify the caller only where the profile allows them (dev and test). */
class ActorProviderTest {
    private fun request(vararg headers: Pair<String, String>) {
        val request = MockHttpServletRequest()
        headers.forEach { (name, value) -> request.addHeader(name, value) }
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
    }

    @AfterEach
    fun clear() = RequestContextHolder.resetRequestAttributes()

    @Test
    fun `headers act as the persona in dev and test`() {
        request("X-Actor" to "middle2", "X-Roles" to "MIDDLE_OFFICE")
        val actor = SpringSecurityActorProvider(SecurityProperties(), headersAllowed = true).current()
        actor.userId shouldBe "middle2"
        actor.roles shouldBe setOf(Role.MIDDLE_OFFICE)
    }

    @Test
    fun `headers are ignored everywhere else`() {
        request("X-Actor" to "mallory", "X-Roles" to "ADMIN")
        SpringSecurityActorProvider(SecurityProperties()).current() shouldBe Actor.SYSTEM
    }
}
