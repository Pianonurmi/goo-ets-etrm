package com.pianonurmi.etrm.showcase

import com.pianonurmi.etrm.config.MigrationHook
import com.pianonurmi.etrm.platform.security.ActorProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.time.Clock

/** Reset of the public showcase (ADR 0033): wipe on start, nightly restart, administrator's reset. */
@Configuration
@Profile("showcase")
@EnableConfigurationProperties(ShowcaseResetProperties::class)
class ShowcaseConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "etrm.showcase", name = ["wipe-on-start"], havingValue = "true", matchIfMissing = true)
    fun showcaseWipe(properties: ShowcaseResetProperties): MigrationHook = ShowcaseWipe(properties.database)

    @Bean
    fun showcaseResetService(
        context: ConfigurableApplicationContext,
        actors: ActorProvider,
        properties: ShowcaseResetProperties,
        clock: Clock,
    ): ShowcaseResetService = ShowcaseResetService(context, actors, properties, clock)
}
