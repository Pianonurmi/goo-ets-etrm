package com.pianonurmi.etrm.demo

import com.pianonurmi.etrm.config.SeedToken
import com.pianonurmi.etrm.web.Personas
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient

/** A persona the demo data acts as; its roles come from the shared catalogue, so tasks land where the UI shows them. */
enum class DemoUser(val id: String) {
    TRADER("trader"),
    TRADER2("trader2"),
    MIDDLE("middle"),
    MIDDLE2("middle2"),
    BACKOFFICE("backoffice"),
    ACCOUNTANT("accountant"),
    CONTROLLER("controller"),
    RISK("risk"),
    RISK2("risk2"),
    AUDITOR("auditor"),
    ADMIN("admin"),
    ;

    val roles: String get() = Personas.byId(id).roles.sorted().joinToString(",")
}

private const val BULK_LIMIT = 100

class DemoRequestFailed(message: String) : RuntimeException(message)

/**
 * Calls the application's own REST API with the persona headers (dev and test profiles, or the showcase with its seed
 * token), so demo data goes through exactly the checks, events and approvals a user's input would.
 */
class DemoClient(baseUrl: String, private val seedToken: String? = null) {
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

    /** A body Jackson serialises, for terms read back from the API. */
    fun postBody(user: DemoUser, path: String, body: Any): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return client.post().uri(path).headers { it.actAs(user) }.contentType(MediaType.APPLICATION_JSON).body(body)
            .retrieve().body(Any::class.java) as? Map<String, Any?> ?: emptyMap()
    }

    /** CSV uploads (statements, payments, confirmations). */
    fun postText(user: DemoUser, path: String, text: String): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return client.post().uri(path).headers { it.actAs(user) }.contentType(MediaType.TEXT_PLAIN).body(text)
            .retrieve().body(Any::class.java) as? Map<String, Any?> ?: emptyMap()
    }

    /** A file upload (market data imports). */
    fun postFile(user: DemoUser, path: String, fileName: String, content: String): Map<String, Any?> {
        val file = object : ByteArrayResource(content.toByteArray()) {
            override fun getFilename(): String = fileName
        }
        val parts = LinkedMultiValueMap<String, Any>().apply { add("file", file) }

        @Suppress("UNCHECKED_CAST")
        return client.post().uri(path).headers { it.actAs(user) }.contentType(MediaType.MULTIPART_FORM_DATA).body(parts)
            .retrieve().body(Any::class.java) as? Map<String, Any?> ?: emptyMap()
    }

    /** Decides tasks in batches (`bulk-decision`), each still checked for four eyes. */
    fun approveAll(user: DemoUser, taskIds: List<String>) {
        taskIds.chunked(BULK_LIMIT).forEach { batch ->
            val ids = batch.joinToString(",", "[", "]") { "\"$it\"" }
            val failed = postForList(user, "/api/v1/workflow/tasks/bulk-decision", """{"taskIds":$ids,"decision":"APPROVE"}""")
                .filter { it["ok"] != true }
            if (failed.isNotEmpty()) throw DemoRequestFailed("Bulk approval failed: $failed")
        }
    }

    /** Open tasks of [type] the user may decide. */
    fun openTasks(user: DemoUser, type: String): List<String> {
        val page = get(user, "/api/v1/workflow/tasks?type=$type&status=OPEN&actionable=true&limit=200") as Map<*, *>
        return (page["items"] as List<*>).filterIsInstance<Map<*, *>>().map { it["id"].toString() }
    }

    fun postForList(user: DemoUser, path: String, json: String): List<Map<String, Any?>> {
        @Suppress("UNCHECKED_CAST")
        return client.post().uri(path).headers { it.actAs(user) }.contentType(MediaType.APPLICATION_JSON).body(json)
            .retrieve().body(Any::class.java) as? List<Map<String, Any?>> ?: emptyList()
    }

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
        // Showcase: the in-memory seed token stands in for the dev profile's open door (ADR 0032).
        seedToken?.let { set(SeedToken.HEADER, it) }
    }
}

fun Map<String, Any?>.id(): String = this["id"]?.toString() ?: throw DemoRequestFailed("Response without id: $this")
