package com.pianonurmi.etrm.product.fixtures

import com.pianonurmi.etrm.product.api.FuturesContractQuery
import com.pianonurmi.etrm.product.api.FuturesContractView

/** No futures contracts listed. For tests of other modules. */
object NoFuturesContracts : FuturesContractQuery {
    override fun futuresContracts(allowanceProductCode: String?, activeOnly: Boolean): List<FuturesContractView> = emptyList()

    override fun futuresContract(code: String): FuturesContractView? = null
}
