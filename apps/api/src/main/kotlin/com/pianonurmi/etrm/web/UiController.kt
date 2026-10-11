package com.pianonurmi.etrm.web

import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.core.env.Environment
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

data class UiConfigView(
    val authMode: UiProperties.AuthMode,
    val oidc: OidcView?,
    val environment: String,
    val version: String,
    val demo: Boolean,
    /** Progress of the demo data (demo profile only); the UI waits while it is RUNNING. */
    val demoSeed: DemoSeedView?,
    /** Personas the user can act as (development headers only); absent with real sign-in. */
    val personas: List<PersonaView>?,
)

enum class DemoSeedState { RUNNING, COMPLETED, FAILED }

data class DemoSeedView(
    val state: DemoSeedState,
    /** The step running, or the one that failed. */
    val step: String?,
    val completedSteps: Int,
    val stepCount: Int,
    val finishedAt: Instant?,
)

/** Implemented by the demo seeder; absent without the demo profile. Keeps `web` free of a dependency on `demo`. */
fun interface DemoSeedStatus {
    fun current(): DemoSeedView?
}

data class PersonaView(val id: String, val name: String, val roles: List<Role>)

data class OidcView(val authority: String, val clientId: String, val scope: String)

data class MeView(
    val userId: String,
    val displayName: String,
    val roles: List<Role>,
    val legalEntityIds: List<UUID>,
    val allEntities: Boolean,
    val authMode: UiProperties.AuthMode,
    /** The demo account signed in (showcase), as opposed to the persona it acts as. */
    val login: String?,
)

/** Bootstrap endpoints for the web UI: public configuration and the signed-in user. */
@RestController
@RequestMapping("/api/v1")
class UiController(
    private val properties: UiProperties,
    private val actors: ActorProvider,
    private val environment: Environment,
    private val build: ObjectProvider<BuildProperties>,
    private val demoSeed: ObjectProvider<DemoSeedStatus>,
) {
    /** Public: the browser reads it before signing in. */
    @GetMapping("/ui-config")
    fun uiConfig(): UiConfigView = UiConfigView(
        authMode = properties.authMode,
        oidc = properties.oidc
            .takeIf { properties.authMode == UiProperties.AuthMode.OIDC }
            ?.let { OidcView(it.authority, it.clientId, it.scope) },
        environment = properties.environment,
        version = build.ifAvailable?.version ?: "dev",
        demo = environment.matchesProfiles("demo"),
        demoSeed = demoSeed.ifAvailable?.current(),
        personas = Personas.ALL
            .takeIf { properties.authMode != UiProperties.AuthMode.OIDC }
            ?.map { PersonaView(it.id, it.name, it.roles.sorted()) },
    )

    @GetMapping("/me")
    fun me(): MeView {
        val actor = actors.current()
        return MeView(
            userId = actor.userId,
            displayName = actor.displayName,
            roles = actor.roles.sorted(),
            legalEntityIds = actor.legalEntityScope.sorted(),
            allEntities = actor.seesAllEntities,
            authMode = properties.authMode,
            login = SecurityContextHolder.getContext().authentication
                ?.takeIf { properties.authMode == UiProperties.AuthMode.SHOWCASE && it is UsernamePasswordAuthenticationToken }
                ?.name,
        )
    }
}
