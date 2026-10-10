package com.pianonurmi.etrm.product.application

import com.pianonurmi.etrm.product.api.criteria.LotAttribute
import com.pianonurmi.etrm.taxonomy.api.TaxonomyChanged
import com.pianonurmi.etrm.taxonomy.api.TaxonomyQuery
import com.pianonurmi.etrm.taxonomy.api.TaxonomyScheme
import com.pianonurmi.etrm.taxonomy.api.TaxonomySnapshot
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/** Caches taxonomy snapshots for criteria binding and specificity; invalidated by [TaxonomyChanged]. */
@Component
class TaxonomySnapshots(private val taxonomy: TaxonomyQuery) {
    private val cache = ConcurrentHashMap<TaxonomyScheme, TaxonomySnapshot>()

    fun snapshot(scheme: TaxonomyScheme): TaxonomySnapshot = cache.computeIfAbsent(scheme) { taxonomy.snapshot(it) }

    fun schemeFor(attribute: LotAttribute): TaxonomyScheme = when (attribute) {
        LotAttribute.ENERGY_SOURCE -> TaxonomyScheme.ENERGY_SOURCE
        LotAttribute.TECHNOLOGY -> TaxonomyScheme.TECHNOLOGY
        else -> throw IllegalArgumentException("$attribute is not a taxonomy attribute")
    }

    fun pathOf(attribute: LotAttribute, code: String): String? = snapshot(schemeFor(attribute)).pathOf(code)

    fun depthOf(attribute: LotAttribute, code: String): Int? = snapshot(schemeFor(attribute)).node(code)?.depth

    fun regionCountries(region: String): Set<String> = snapshot(TaxonomyScheme.REGION).regionCountries(region)

    @EventListener
    fun on(event: TaxonomyChanged) {
        cache.remove(event.scheme)
    }

    fun invalidateAll() = cache.clear()
}
