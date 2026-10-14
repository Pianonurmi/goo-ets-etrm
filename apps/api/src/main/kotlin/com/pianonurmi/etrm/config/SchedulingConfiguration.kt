package com.pianonurmi.etrm.config

import com.pianonurmi.etrm.platform.jobs.JobRun
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.core.SimpleLock
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import java.util.Optional
import javax.sql.DataSource

/**
 * Cluster-wide locks for scheduled jobs: every `@SchedulerLock` job runs on one instance at a time. Locks live in
 * `etrm_platform.shedlock` and use database time, so clock skew between instances does not matter.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
class SchedulingConfiguration {
    @Bean
    fun lockProvider(dataSource: DataSource): LockProvider = RunRecordingLockProvider(
        JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(JdbcTemplate(dataSource))
                .withTableName("etrm_platform.shedlock")
                .usingDbTime()
                .build(),
        ),
    )
}

/** Tells the job metrics that this instance got the lock and runs the job (`JobMetrics`). */
class RunRecordingLockProvider(private val delegate: LockProvider) : LockProvider {
    override fun lock(lockConfiguration: LockConfiguration): Optional<SimpleLock> =
        delegate.lock(lockConfiguration).also { if (it.isPresent) JobRun.lockAcquired() }
}
