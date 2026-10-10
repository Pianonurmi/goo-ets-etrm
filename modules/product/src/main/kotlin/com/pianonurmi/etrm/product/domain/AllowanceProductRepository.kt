package com.pianonurmi.etrm.product.domain

import com.pianonurmi.etrm.product.api.AllowanceProductView
import com.pianonurmi.etrm.product.api.FuturesContractView

interface AllowanceProductRepository {
    fun all(activeOnly: Boolean): List<AllowanceProductView>

    fun byCode(code: String): AllowanceProductView?
}

interface FuturesContractRepository {
    fun all(allowanceProductCode: String?, activeOnly: Boolean): List<FuturesContractView>

    fun byCode(code: String): FuturesContractView?

    fun insert(contract: FuturesContractView)

    fun setActive(code: String, active: Boolean)
}
