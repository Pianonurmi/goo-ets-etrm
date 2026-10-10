package com.pianonurmi.etrm.config

import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource

/**
 * Cluster-wide locks for scheduled jobs: every `@SchedulerLock` job runs on one instance at a time. Locks live in
 * `etrm_platform.shedlock` and use database time, so clock skew between instances does not matter.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
class SchedulingConfiguration {
    @Bean
    fun lockProvider(dataSource: DataSource): LockProvider =
        JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(JdbcTemplate(dataSource))
                .withTableName("etrm_platform.shedlock")
                .usingDbTime()
                .build(),
        )
}
