package com.pianonurmi.etrm.allocation.domain

import com.pianonurmi.etrm.inventory.api.LotStatus
import com.pianonurmi.etrm.inventory.api.LotView
import java.util.UUID

/** Groups HELD lots into classes after deducting quantities soft-reserved by other active proposals. */
object LotClassifier {
    fun classify(lots: List<LotView>, softReserved: Map<UUID, Long>): List<LotClass> = lots
        .asSequence()
        .filter { it.status == LotStatus.HELD }
        .map { AvailableLot(it, it.quantityMwh - (softReserved[it.id] ?: 0L)) }
        .filter { it.availableMwh > 0 }
        .groupBy { it.lot.classKey }
        .entries
        .sortedBy { it.key }
        .mapIndexed { index, (key, available) -> LotClass(index, key, available.sortedWith(compareBy({ it.lot.expiryDate }, { it.lot.id }))) }
}
