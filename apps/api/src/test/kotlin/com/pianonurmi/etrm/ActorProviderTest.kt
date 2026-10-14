package com.pianonurmi.etrm

import com.pianonurmi.etrm.config.SecurityProperties
import com.pianonurmi.etrm.config.SpringSecurityActorProvider
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.Role
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
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
    fun `headers are ignored everywhere else, and a request without a user acts as nobody`() {
        request("X-Actor" to "mallory", "X-Roles" to "ADMIN")
        val actor = SpringSecurityActorProvider(SecurityProperties()).current()
        actor shouldBe Actor.ANONYMOUS
        actor.roles shouldBe emptySet()
        actor.seesAllEntities shouldBe false
    }

    @Test
    fun `outside a web request the system acts`() {
        SpringSecurityActorProvider(SecurityProperties()).current() shouldBe Actor.SYSTEM
    }

    @Test
    fun `a token never grants the system role`() {
        val jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("u-1")
            .claim("roles", listOf("SYSTEM", "TRADER")).claim("legal_entities", listOf("*")).build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        try {
            SpringSecurityActorProvider(SecurityProperties()).current().roles shouldBe setOf(Role.TRADER)
        } finally {
            SecurityContextHolder.clearContext()
        }
    }
}
