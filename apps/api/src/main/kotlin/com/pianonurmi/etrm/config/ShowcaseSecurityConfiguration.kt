package com.pianonurmi.etrm.config

import com.pianonurmi.etrm.web.UiProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.ExceptionTranslationFilter
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler
import org.springframework.security.web.csrf.CsrfFilter
import org.springframework.security.web.util.matcher.RequestMatcher

/**
 * The public showcase (ADR 0032): visitors sign in with a demo account, then act as any persona of the catalogue by
 * sending `X-Persona`. Sessions are cookies with CSRF protection (the SPA reads `XSRF-TOKEN` and sends
 * `X-XSRF-TOKEN`); the login is throttled; the demo seeder signs in with an in-memory token instead.
 */
@Configuration
@Profile("showcase")
@EnableConfigurationProperties(ShowcaseProperties::class)
class ShowcaseSecurityConfiguration {
    private val log = LoggerFactory.getLogger(ShowcaseSecurityConfiguration::class.java)

    @Bean
    fun passwordEncoder(): PasswordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()

    @Bean
    fun showcaseUsers(properties: ShowcaseProperties, encoder: PasswordEncoder): InMemoryUserDetailsManager {
        val users = ShowcaseUsers.parse(properties, encoder)
        log.info("Showcase sign-in enabled for {} demo user(s): {}", users.size, users.joinToString { it.username })
        return InMemoryUserDetailsManager(users)
    }

    @Bean
    fun seedToken(): SeedToken = SeedToken()

    @Bean
    fun loginThrottle(): LoginThrottle = LoginThrottle()

    @Bean
    fun showcaseChain(http: HttpSecurity, ui: UiProperties, seedToken: SeedToken, throttle: LoginThrottle): SecurityFilterChain = http
        .csrf {
            it.spa()
            // The seeder authenticates per request with its token (no cookie), so it cannot be a cross-site forgery.
            it.ignoringRequestMatchers(RequestMatcher { SecurityContextHolder.getContext().authentication is SeedAuthentication })
        }
        .sessionManagement {
            it.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
            it.sessionFixation { fixation -> fixation.changeSessionId() }
        }
        .headers { SecurityConfiguration.browserHeaders(it, ui) }
        .authorizeHttpRequests {
            it.requestMatchers("/actuator/health/**").permitAll()
            it.requestMatchers(HttpMethod.GET, "/api/v1/ui-config").permitAll()
            it.requestMatchers("/api/v1/auth/**").permitAll()
            it.requestMatchers("/error").permitAll()
            it.requestMatchers(SecurityConfiguration.SPA_ROUTES).permitAll()
            it.anyRequest().authenticated()
        }
        .formLogin {
            it.loginProcessingUrl(LOGIN_PATH)
            it.successHandler { request, response, _ ->
                throttle.succeeded(request.getParameter("username"))
                response.status = HttpStatus.NO_CONTENT.value()
            }
            it.failureHandler { request, response, _ ->
                throttle.failed(request.getParameter("username"), request.remoteAddr)
                response.problem(HttpStatus.UNAUTHORIZED.value(), "unauthenticated", "Wrong user name or password")
            }
            it.permitAll()
        }
        .logout {
            it.logoutUrl("/api/v1/auth/logout")
            it.logoutSuccessHandler(HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
        }
        .exceptionHandling { it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) }
        .addFilterBefore(SeedTokenFilter(seedToken), CsrfFilter::class.java)
        .addFilterBefore(LoginThrottleFilter(throttle, LOGIN_PATH), UsernamePasswordAuthenticationFilter::class.java)
        .addFilterAfter(CsrfCookieFilter(), CsrfFilter::class.java)
        .addFilterAfter(PersonaFilter(), ExceptionTranslationFilter::class.java)
        .build()

    companion object {
        const val LOGIN_PATH = "/api/v1/auth/login"

        /** Hashes a password for ETRM_DEMO_USERS, so the clear text need not sit in the environment. */
        fun bcrypt(password: String): String = "{bcrypt}" + BCryptPasswordEncoder().encode(password)
    }
}
