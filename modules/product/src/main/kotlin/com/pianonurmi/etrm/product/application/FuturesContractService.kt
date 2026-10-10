package com.pianonurmi.etrm.product.application

import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.product.api.CreateFuturesContract
import com.pianonurmi.etrm.product.api.FuturesContractCommand
import com.pianonurmi.etrm.product.api.FuturesContractQuery
import com.pianonurmi.etrm.product.api.FuturesContractView
import com.pianonurmi.etrm.product.api.ProductCatalogueQuery
import com.pianonurmi.etrm.product.domain.FuturesContractRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.Currency

@Service
@Transactional(readOnly = true)
class FuturesContractService(
    private val repository: FuturesContractRepository,
    private val catalogue: ProductCatalogueQuery,
    private val actorProvider: ActorProvider,
) : FuturesContractQuery, FuturesContractCommand {
    override fun futuresContracts(allowanceProductCode: String?, activeOnly: Boolean): List<FuturesContractView> =
        repository.all(allowanceProductCode?.uppercase(), activeOnly)

    override fun futuresContract(code: String): FuturesContractView? = repository.byCode(code.uppercase())

    @Transactional
    override fun create(command: CreateFuturesContract): FuturesContractView {
        actorProvider.current().requireRole(Role.MIDDLE_OFFICE)
        val code = command.code.trim().uppercase()
        if (repository.byCode(code) != null) throw ConflictException("Futures contract $code already exists")
        val product = catalogue.allowanceProduct(command.allowanceProductCode) ?: throw NotFoundException("AllowanceProduct", command.allowanceProductCode)
        if (!Regex("^[A-Z0-9]{4}$").matches(command.venueMic)) throw ValidationException("Venue must be an ISO 10383 MIC, e.g. XEEE")
        if (command.lastTradingDay.isAfter(command.deliveryDate)) throw ValidationException("The last trading day must not be after delivery")
        if (command.contractSize.signum() <= 0) throw ValidationException("Contract size must be positive")
        val contract = FuturesContractView(
            code = code,
            allowanceProductCode = product.code,
            venueMic = command.venueMic,
            deliveryMonth = command.deliveryMonth,
            deliveryDate = command.deliveryDate,
            lastTradingDay = command.lastTradingDay,
            contractSize = command.contractSize,
            currency = Currency.getInstance(command.currency.uppercase()).currencyCode,
            active = true,
        )
        repository.insert(contract)
        return contract
    }

    @Transactional
    override fun deactivate(code: String): FuturesContractView {
        actorProvider.current().requireRole(Role.MIDDLE_OFFICE)
        val current = repository.byCode(code.uppercase()) ?: throw NotFoundException("FuturesContract", code)
        repository.setActive(current.code, false)
        return current.copy(active = false)
    }
}
