package com.pianonurmi.etrm

import org.testcontainers.DockerClientFactory

/** Tests that start containers run where Docker is available (CI) and are skipped elsewhere instead of failing. */
object Docker {
    @JvmStatic
    fun available(): Boolean = runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
}
