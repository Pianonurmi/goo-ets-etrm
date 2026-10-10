package com.pianonurmi.etrm.product.domain

import com.pianonurmi.etrm.product.api.AllowanceProductView

interface AllowanceProductRepository {
    fun all(activeOnly: Boolean): List<AllowanceProductView>

    fun byCode(code: String): AllowanceProductView?
}
