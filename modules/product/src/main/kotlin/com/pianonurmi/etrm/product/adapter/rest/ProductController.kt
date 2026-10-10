package com.pianonurmi.etrm.product.adapter.rest

import com.pianonurmi.etrm.product.api.AllowanceProductView
import com.pianonurmi.etrm.product.api.CreateFuturesContract
import com.pianonurmi.etrm.product.api.CreateProductBucket
import com.pianonurmi.etrm.product.api.FuturesContractCommand
import com.pianonurmi.etrm.product.api.FuturesContractQuery
import com.pianonurmi.etrm.product.api.FuturesContractView
import com.pianonurmi.etrm.product.api.ProductBucketCommand
import com.pianonurmi.etrm.product.api.ProductBucketQuery
import com.pianonurmi.etrm.product.api.ProductBucketView
import com.pianonurmi.etrm.product.api.ProductCatalogueQuery
import com.pianonurmi.etrm.product.api.UpdateProductBucket
import com.pianonurmi.etrm.product.api.criteria.CriteriaCatalogue
import com.pianonurmi.etrm.product.api.criteria.CriteriaService
import com.pianonurmi.etrm.product.api.criteria.Criterion
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal

@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "Products", description = "Product buckets, allowance products and the GoO criteria language")
class ProductController(
    private val buckets: ProductBucketQuery,
    private val bucketCommand: ProductBucketCommand,
    private val catalogue: ProductCatalogueQuery,
    private val criteria: CriteriaService,
    private val futures: FuturesContractQuery,
    private val futuresCommand: FuturesContractCommand,
) {
    @GetMapping("/buckets")
    fun buckets(
        @RequestParam(defaultValue = "true") activeOnly: Boolean,
    ): List<ProductBucketView> = buckets.buckets(activeOnly)

    @GetMapping("/buckets/{code}")
    fun bucket(
        @PathVariable code: String,
    ): ProductBucketView = buckets.requireBucket(code)

    @PostMapping("/buckets")
    @ResponseStatus(HttpStatus.CREATED)
    fun createBucket(
        @Valid @RequestBody body: CreateProductBucket,
    ): ProductBucketView = bucketCommand.create(body)

    @PatchMapping("/buckets/{code}")
    fun updateBucket(
        @PathVariable code: String,
        @Valid @RequestBody body: UpdateProductBucket,
    ): ProductBucketView = bucketCommand.update(code, body)

    @GetMapping("/allowances")
    fun allowances(
        @RequestParam(defaultValue = "true") activeOnly: Boolean,
    ): List<AllowanceProductView> = catalogue.allowanceProducts(activeOnly)

    @GetMapping("/futures-contracts")
    fun futuresContracts(
        @RequestParam(required = false) allowanceProductCode: String?,
        @RequestParam(defaultValue = "true") activeOnly: Boolean,
    ): List<FuturesContractView> = futures.futuresContracts(allowanceProductCode, activeOnly)

    @PostMapping("/futures-contracts")
    @ResponseStatus(HttpStatus.CREATED)
    fun createFuturesContract(
        @RequestBody body: CreateFuturesContract,
    ): FuturesContractView = futuresCommand.create(body)

    @PostMapping("/futures-contracts/{code}/deactivate")
    fun deactivateFuturesContract(
        @PathVariable code: String,
    ): FuturesContractView = futuresCommand.deactivate(code)

    /** Fields, operators and value sources of the criteria language, for criteria builders. */
    @GetMapping("/criteria/catalogue")
    fun criteriaCatalogue(): CriteriaCatalogue = criteria.catalogue()

    /** Validates a criterion, returning its canonical JSON, description and specificity. */
    @PostMapping("/criteria/validate")
    fun validate(
        @RequestBody criterion: Criterion,
    ): CriteriaValidation =
        CriteriaValidation(criteria.toJson(criterion), criteria.describe(criterion), criteria.specificity(criterion))
}

data class CriteriaValidation(val json: String, val summary: String, val specificity: BigDecimal)
