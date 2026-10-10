package com.pianonurmi.build

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * Marker build service: test tasks of all modules share one integration test database (`ETRM_TEST_DB_URL`) and
 * reset its schemas, so they must not run concurrently. Registering with `maxParallelUsages = 1` serialises them
 * while the rest of the build stays parallel.
 */
abstract class SharedTestDatabase : BuildService<BuildServiceParameters.None>
