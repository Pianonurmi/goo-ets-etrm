package com.pianonurmi.etrm.platform.test

import org.slf4j.LoggerFactory
import java.util.concurrent.ThreadLocalRandom

/**
 * Seeds of the property tests (ADR 0049). A normal build uses each test's fixed seed, so a pull request is
 * reproducible; `-Detrm.proptest.seed=<n>` replays one seed everywhere, and `-Detrm.proptest.seed=random` (the
 * nightly run, `.github/workflows/property-nightly.yml`) draws a new seed per test and logs it for the replay.
 */
object PropertySeeds {
    private val log = LoggerFactory.getLogger(PropertySeeds::class.java)
    private const val PROPERTY = "etrm.proptest.seed"

    fun seed(default: Long): Long {
        val configured = System.getProperty(PROPERTY)?.trim().orEmpty()
        return when {
            configured.isEmpty() -> default
            configured.equals("random", ignoreCase = true) -> ThreadLocalRandom.current().nextLong().also {
                log.warn("Property test seed {} (replay with -D{}={})", it, PROPERTY, it)
            }
            else -> requireNotNull(configured.toLongOrNull()) { "$PROPERTY must be a number or 'random', was '$configured'" }
        }
    }
}
