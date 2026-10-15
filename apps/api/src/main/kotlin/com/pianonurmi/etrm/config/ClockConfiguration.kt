package com.pianonurmi.etrm.config

import com.pianonurmi.etrm.platform.time.ApplicationClock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class ClockConfiguration {
    /** The one clock of the application (ADR 0050), also behind the time stamped on domain events. */
    @Bean
    fun clock(): Clock = Clock.systemUTC().also(ApplicationClock::install)
}
