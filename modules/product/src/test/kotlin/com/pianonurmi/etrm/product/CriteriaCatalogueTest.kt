package com.pianonurmi.etrm.product

import com.pianonurmi.etrm.product.api.criteria.CriteriaOperator
import com.pianonurmi.etrm.product.api.criteria.CriteriaValueSource
import com.pianonurmi.etrm.product.api.criteria.LotAttribute
import com.pianonurmi.etrm.product.domain.CriteriaCatalogueBuilder
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CriteriaCatalogueTest {
    private val catalogue = CriteriaCatalogueBuilder.catalogue

    @Test
    fun `every attribute is described once, in declaration order`() {
        catalogue.fields.map { it.attribute } shouldContainExactly LotAttribute.entries
    }

    @Test
    fun `each field offers only operators its kind accepts`() {
        catalogue.fields.filter { field ->
            !CriteriaCatalogueBuilder.operatorsByKind.getValue(field.kind).containsAll(field.operators) || field.operators.isEmpty()
        }.shouldBeEmpty()
    }

    @Test
    fun `taxonomy fields name their scheme and enum fields their values`() {
        catalogue.fields.filter { it.valueSource == CriteriaValueSource.TAXONOMY && it.taxonomyScheme == null }.shouldBeEmpty()
        catalogue.fields.filter { it.valueSource == CriteriaValueSource.ENUM && it.values.isEmpty() }.shouldBeEmpty()
        catalogue.fields.single { it.attribute == LotAttribute.ENERGY_SOURCE }.operators shouldBe listOf(CriteriaOperator.DESCENDANT_OF)
    }
}
