package com.pianonurmi.etrm.web

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI

/** What the browser needs before it can sign in (ADR 0026). */
@ConfigurationProperties(prefix = "etrm.ui")
data class UiProperties(
    /** OIDC in production; DEV_HEADERS in the dev and test profiles, where the UI acts as a chosen persona. */
    val authMode: AuthMode = AuthMode.OIDC,
    val oidc: Oidc = Oidc(),
    /** Shown in the header, e.g. "TEST" or "PROD"; empty shows nothing. */
    val environment: String = "",
) {
    data class Oidc(
        val authority: String = "http://localhost:8081/realms/etrm",
        val clientId: String = "etrm-web",
        val scope: String = "openid profile",
    ) {
        /** Scheme, host and port of the identity provider, for the content security policy. */
        fun origin(): String = URI.create(authority).let { uri -> URI(uri.scheme, null, uri.host, uri.port, null, null, null).toString() }
    }

    enum class AuthMode { OIDC, DEV_HEADERS }
}
