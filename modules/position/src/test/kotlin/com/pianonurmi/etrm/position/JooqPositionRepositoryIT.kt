package com.pianonurmi.etrm.position

import com.pianonurmi.etrm.platform.test.TestDatabase
import com.pianonurmi.etrm.position.adapter.jooq.JooqPositionRepository
import com.pianonurmi.etrm.position.api.PositionSnapshotView
import com.pianonurmi.etrm.position.api.SnapshotKind
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Position snapshots: one per entity, kind and day, replaced when taken again, and read by date range. */
class JooqPositionRepositoryIT {
    private lateinit var repository: JooqPositionRepository
    private val entity = UUID.randomUUID()
    private val takenAt = Instant.parse("2026-10-16T18:00:00Z")

    @BeforeEach
    fun setUp() {
        TestDatabase.migrate("position")
        repository = JooqPositionRepository(TestDatabase.dsl)
    }

    private fun snapshot(kind: SnapshotKind, day: LocalDate, data: Map<String, Any?>, at: Instant = takenAt) =
        PositionSnapshotView(UUID.randomUUID(), entity, kind, day, at, data)

    @Test
    fun `a snapshot taken again on the same day replaces the first one`() {
        val day = LocalDate.of(2026, 10, 15)
        repository.upsertSnapshot(snapshot(SnapshotKind.GOO, day, mapOf("totalLiveMwh" to 100)))
        repository.upsertSnapshot(snapshot(SnapshotKind.GOO, day, mapOf("totalLiveMwh" to 120), takenAt.plusSeconds(60)))

        val stored = repository.snapshot(entity, SnapshotKind.GOO, day)!!
        stored.data shouldBe mapOf("totalLiveMwh" to 120)
        stored.takenAt shouldBe takenAt.plusSeconds(60)
        repository.snapshot(entity, SnapshotKind.ETS, day) shouldBe null
    }

    @Test
    fun `snapshots are read by kind within the dates, oldest first`() {
        listOf(10, 12, 14).forEach { d ->
            repository.upsertSnapshot(snapshot(SnapshotKind.ETS, LocalDate.of(2026, 10, d), mapOf("day" to d)))
        }
        repository.upsertSnapshot(snapshot(SnapshotKind.GOO, LocalDate.of(2026, 10, 12), mapOf("day" to 12)))

        repository.snapshots(entity, SnapshotKind.ETS, LocalDate.of(2026, 10, 11), LocalDate.of(2026, 10, 14))
            .map { it.asOfDate.dayOfMonth } shouldContainExactly listOf(12, 14)
        repository.snapshots(UUID.randomUUID(), SnapshotKind.ETS, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)) shouldBe emptyList()
    }
}
