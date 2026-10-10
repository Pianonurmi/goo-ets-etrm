package com.pianonurmi.etrm.product.application

import com.pianonurmi.etrm.product.api.AllowanceProductView
import com.pianonurmi.etrm.product.api.ProductCatalogueQuery
import com.pianonurmi.etrm.product.domain.AllowanceProductRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class ProductCatalogueService(private val repository: AllowanceProductRepository) : ProductCatalogueQuery {
    override fun allowanceProducts(activeOnly: Boolean): List<AllowanceProductView> = repository.all(activeOnly)

    override fun allowanceProduct(code: String): AllowanceProductView? = repository.byCode(code.uppercase())
}
