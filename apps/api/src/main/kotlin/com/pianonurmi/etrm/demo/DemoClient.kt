package com.pianonurmi.etrm.demo

import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient

/** A dev persona the demo data acts as; the same ids as the web UI's personas, so tasks land in their inboxes. */
enum class DemoUser(val id: String, val roles: String) {
    TRADER("trader", "TRADER"),
    MIDDLE("middle", "MIDDLE_OFFICE"),
    BACKOFFICE("backoffice", "BACK_OFFICE,REGISTRY_OPERATOR"),
    ACCOUNTANT("accountant", "ACCOUNTANT"),
    RISK("risk", "RISK,MIDDLE_OFFICE"),
    RISK2("risk2", "RISK,MIDDLE_OFFICE"),
    ADMIN("admin", "ADMIN"),
}

class DemoRequestFailed(message: String) : RuntimeException(message)

/**
 * Calls the application's own REST API with the dev persona headers (dev and test profiles only), so demo data
 * goes through exactly the checks, events and approvals a user's input would.
 */
class DemoClient(baseUrl: String) {
    private val client = RestClient.builder()
        .baseUrl(baseUrl)
        .defaultStatusHandler(HttpStatusCode::isError) { request, response ->
            val body = response.body.readAllBytes().toString(Charsets.UTF_8)
            throw DemoRequestFailed("${request.method} ${request.uri} answered ${response.statusCode.value()}: $body")
        }
        .build()

    fun get(user: DemoUser, path: String): Any? = client.get().uri(path).headers { it.actAs(user) }.retrieve().body(Any::class.java)

    fun getList(user: DemoUser, path: String): List<Map<String, Any?>> {
        @Suppress("UNCHECKED_CAST")
        return get(user, path) as? List<Map<String, Any?>> ?: emptyList()
    }

    fun post(user: DemoUser, path: String, json: String? = null): Map<String, Any?> = send(client.post().uri(path), user, json)

    fun put(user: DemoUser, path: String, json: String): Map<String, Any?> = send(client.put().uri(path), user, json)

    fun patch(user: DemoUser, path: String, json: String): Map<String, Any?> = send(client.patch().uri(path), user, json)

    private fun send(spec: RestClient.RequestBodySpec, user: DemoUser, json: String?): Map<String, Any?> {
        spec.headers { it.actAs(user) }
        if (json != null) spec.contentType(MediaType.APPLICATION_JSON).body(json)
        @Suppress("UNCHECKED_CAST")
        return spec.retrieve().body(Any::class.java) as? Map<String, Any?> ?: emptyMap()
    }

    /** Approves the open workflow task of [type] for [subject] as [user] (the second pair of eyes). */
    fun approveTask(user: DemoUser, type: String, subject: String) {
        val page = get(user, "/api/v1/workflow/tasks?subjectRef=$subject&type=$type&status=OPEN") as Map<*, *>
        val task = (page["items"] as List<*>).filterIsInstance<Map<*, *>>().firstOrNull()
            ?: throw DemoRequestFailed("No open $type task for $subject")
        post(user, "/api/v1/workflow/tasks/${task["id"]}/approve", "{}")
    }

    private fun org.springframework.http.HttpHeaders.actAs(user: DemoUser) {
        set("X-Actor", user.id)
        set("X-Roles", user.roles)
    }
}

fun Map<String, Any?>.id(): String = this["id"]?.toString() ?: throw DemoRequestFailed("Response without id: $this")
