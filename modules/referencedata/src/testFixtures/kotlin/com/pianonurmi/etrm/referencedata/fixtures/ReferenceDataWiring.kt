package com.pianonurmi.etrm.referencedata.fixtures

import com.pianonurmi.etrm.referencedata.adapter.jooq.JooqReferenceDataRepository
import com.pianonurmi.etrm.referencedata.application.ReferenceDataService
import com.pianonurmi.etrm.taxonomy.adapter.jooq.JooqTaxonomyRepository
import com.pianonurmi.etrm.taxonomy.application.TaxonomyService
import org.jooq.DSLContext
import org.springframework.context.ApplicationEventPublisher

/** Reference data and its taxonomy as the application wires them, for hand-wired tests of any module. */
class ReferenceDataWiring(dsl: DSLContext, events: ApplicationEventPublisher) {
    val taxonomy = TaxonomyService(JooqTaxonomyRepository(dsl), events)
    val referenceData = ReferenceDataService(JooqReferenceDataRepository(dsl), taxonomy, events)
}
