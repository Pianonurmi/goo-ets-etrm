package com.pianonurmi.etrm.config

import org.springframework.boot.web.server.context.WebServerInitializedEvent
import org.springframework.context.ApplicationListener

/**
 * The port the actuator listens on when it has its own (`management.server.port`, e.g. 9090 in Kubernetes), known
 * once that server has started; requests there may reach the actuator without a token.
 */
class ManagementPort : ApplicationListener<WebServerInitializedEvent> {
    @Volatile
    private var port: Int = -1

    override fun onApplicationEvent(event: WebServerInitializedEvent) {
        if (event.applicationContext.serverNamespace == "management") port = event.webServer.port
    }

    fun matches(localPort: Int): Boolean = port > 0 && localPort == port
}
