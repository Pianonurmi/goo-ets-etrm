package com.pianonurmi.etrm.platform.ids

import com.fasterxml.uuid.Generators
import com.fasterxml.uuid.impl.TimeBasedEpochRandomGenerator
import java.util.UUID

/**
 * Time-ordered UUID (version 7) generator. UUIDv7 keeps B-tree indexes append-friendly and
 * makes identifiers sortable by creation time without leaking a sequence.
 */
object Ids {
    private val generator: TimeBasedEpochRandomGenerator = Generators.timeBasedEpochRandomGenerator()

    fun newId(): UUID = generator.generate()
}

/** Marker for strongly typed identifiers wrapping a UUID. */
interface TypedId {
    val value: UUID
}
