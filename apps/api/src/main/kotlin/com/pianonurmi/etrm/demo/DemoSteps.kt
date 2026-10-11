package com.pianonurmi.etrm.demo

import com.pianonurmi.etrm.platform.json.Json
import com.pianonurmi.etrm.web.DemoSeedState
import org.slf4j.LoggerFactory
import java.util.UUID

/** One unit of demo data. It runs once per seed run and leaves what later steps need in the [DemoContext]. */
class DemoStep(val name: String, val run: (DemoContext) -> Unit)

/**
 * What the steps share: the API client, the calendar, and the ids created so far. Values a step puts are stored
 * with the step in the run log (as JSON), so a resumed run gets them back without repeating the step.
 */
class DemoContext(val api: DemoClient, val calendar: DemoCalendar) {
    private val values = linkedMapOf<String, Any>()
    private val produced = linkedMapOf<String, Any>()

    fun put(key: String, value: Any) {
        values[key] = value
        produced[key] = value
    }

    fun <T : Any> get(key: String, type: Class<T>): T {
        val value = checkNotNull(values[key]) { "Demo value '$key' is missing: the step that creates it has not run" }
        return if (type.isInstance(value)) type.cast(value) else Json.mapper.convertValue(value, type)
    }

    inline fun <reified T : Any> get(key: String): T = get(key, T::class.java)

    /** The values put since the last call, as JSON for the run log. */
    internal fun takeProduced(): String = Json.mapper.writeValueAsString(produced).also { produced.clear() }

    /** Values of a step that completed in an earlier attempt. */
    internal fun restore(json: String) {
        @Suppress("UNCHECKED_CAST")
        (Json.mapper.readValue(json, Map::class.java) as Map<String, Any>).forEach { (key, value) -> values[key] = value }
    }
}

/**
 * Runs the steps of one seed run in order, skipping those an earlier attempt completed (their values come back from
 * the log). Stops at the first failure, which the log records with its message (the failed request and response for
 * an API call) and duration.
 */
class DemoSeedRunner(private val runLog: DemoRunLog) {
    private val log = LoggerFactory.getLogger(DemoSeedRunner::class.java)

    fun execute(run: UUID, steps: List<DemoStep>, context: DemoContext) {
        val done = runLog.steps(run).filter { it.status == DemoSeedState.COMPLETED }.associateBy { it.name }
        if (done.isNotEmpty()) log.info("Resuming demo data: {} of {} steps already done", done.size, steps.size)
        steps.forEachIndexed { index, step ->
            val previous = done[step.name]
            if (previous != null) {
                previous.outputs?.let(context::restore)
                return@forEachIndexed
            }
            runLog.stepStarted(run, index + 1, step.name)
            val started = System.nanoTime()
            try {
                step.run(context)
            } catch (failure: RuntimeException) {
                runLog.stepFailed(run, step.name, elapsedMs(started), failure.message ?: failure.javaClass.name)
                log.error("Demo data step {}/{} '{}' failed; the next start resumes here", index + 1, steps.size, step.name, failure)
                throw failure
            }
            runLog.stepFinished(run, step.name, elapsedMs(started), context.takeProduced())
            log.info("Demo data step {}/{} '{}' done in {} ms", index + 1, steps.size, step.name, elapsedMs(started))
        }
        runLog.completed(run)
    }

    private fun elapsedMs(startedNanos: Long) = (System.nanoTime() - startedNanos) / 1_000_000
}
