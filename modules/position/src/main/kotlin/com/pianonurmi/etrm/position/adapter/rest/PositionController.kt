package com.pianonurmi.etrm.position.adapter.rest

import com.pianonurmi.etrm.position.api.ComplianceLadder
import com.pianonurmi.etrm.position.api.GooPositionReport
import com.pianonurmi.etrm.position.api.PositionCommand
import com.pianonurmi.etrm.position.api.PositionQuery
import com.pianonurmi.etrm.position.api.PositionSnapshotView
import com.pianonurmi.etrm.position.api.SnapshotKind
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/v1/positions")
@Tag(name = "Positions", description = "GoO coverage positions, expiry ladder, EU ETS compliance ladder and snapshots")
class PositionController(private val query: PositionQuery, private val command: PositionCommand) {
    @GetMapping("/goo")
    fun goo(
        @RequestParam legalEntityId: UUID,
    ): GooPositionReport = query.gooPosition(legalEntityId)

    @GetMapping("/ets")
    fun ets(
        @RequestParam legalEntityId: UUID,
        @RequestParam complianceYear: Int,
    ): ComplianceLadder = query.complianceLadder(legalEntityId, complianceYear)

    @GetMapping("/snapshots")
    fun snapshots(
        @RequestParam legalEntityId: UUID,
        @RequestParam kind: SnapshotKind,
        @RequestParam from: LocalDate,
        @RequestParam to: LocalDate,
    ): List<PositionSnapshotView> =
        query.snapshots(legalEntityId, kind, from, to)

    @PostMapping("/snapshots")
    fun takeSnapshots(
        @RequestParam legalEntityId: UUID,
        @RequestParam(required = false) asOf: LocalDate?,
    ): List<PositionSnapshotView> =
        command.takeSnapshots(legalEntityId, asOf ?: LocalDate.now())
}
