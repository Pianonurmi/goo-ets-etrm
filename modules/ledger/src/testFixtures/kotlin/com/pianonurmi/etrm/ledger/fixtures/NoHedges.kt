package com.pianonurmi.etrm.ledger.fixtures

import com.pianonurmi.etrm.ledger.api.HedgeDetail
import com.pianonurmi.etrm.ledger.api.HedgeQuery
import com.pianonurmi.etrm.ledger.api.HedgeRelationshipView
import com.pianonurmi.etrm.ledger.api.HedgeStatus
import com.pianonurmi.etrm.platform.errors.NotFoundException
import java.util.UUID

/** No cash flow hedges designated. For tests of other modules. */
object NoHedges : HedgeQuery {
    override fun hedges(legalEntityId: UUID, status: HedgeStatus?): List<HedgeRelationshipView> = emptyList()

    override fun hedge(id: UUID): HedgeDetail = throw NotFoundException("HedgeRelationship", id)
}
