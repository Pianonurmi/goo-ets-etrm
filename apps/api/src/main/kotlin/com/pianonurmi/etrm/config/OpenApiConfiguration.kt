package com.pianonurmi.etrm.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springdoc.core.customizers.OperationCustomizer
import org.springdoc.core.utils.SpringDocUtils
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.Currency
import io.swagger.v3.oas.annotations.Operation as OperationAnnotation

/**
 * OpenAPI document used by Swagger UI and by the web client generator (ADR 0027). Operation ids are
 * `<controller>_<method>` so they are unique and stable; a handler that needs another name declares
 * `@Operation(operationId = ...)`.
 */
@Configuration
class OpenApiConfiguration {
    init {
        // Jackson writes a currency as its ISO code ("EUR"), not as the bean swagger-core would describe.
        SpringDocUtils.getConfig().replaceWithSchema(Currency::class.java, Schema<String>().apply { addType("string") }.example("EUR"))
    }

    @Bean
    fun etrmOpenApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("GoO and EU ETS ETRM API")
                .version("v1")
                .description("Trading, inventory, allocation, registry, settlement, valuation, risk and accounting for GoO and EU ETS allowances."),
        )
        // A relative server keeps the document independent of the host it was fetched from.
        .servers(listOf(Server().url("/")))
        .components(
            Components().addSecuritySchemes(
                BEARER,
                SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"),
            ),
        )
        .addSecurityItem(SecurityRequirement().addList(BEARER))

    @Bean
    fun operationIds(): OperationCustomizer = OperationCustomizer { operation, handler ->
        val explicit = handler.getMethodAnnotation(OperationAnnotation::class.java)?.operationId
        if (explicit.isNullOrBlank()) {
            operation.operationId = operationId(handler.beanType.simpleName, handler.method.name)
        }
        operation
    }

    @Bean
    fun kotlinRequiredProperties(): KotlinRequiredProperties = KotlinRequiredProperties()

    /** Every operation can answer with an RFC 9457 problem (see ProblemDetailsHandler). */
    @Bean
    fun problemResponses(): OpenApiCustomizer = OpenApiCustomizer { api ->
        api.components.addSchemas(PROBLEM, problemSchema())
        api.components.addSchemas("Violation", violationSchema())
        api.paths?.values?.flatMap { it.readOperations() }?.forEach { operation ->
            operation.responses.addApiResponse(
                "default",
                ApiResponse()
                    .description("Problem details")
                    .content(Content().addMediaType("application/problem+json", MediaType().schema(Schema<Any>().`$ref`("#/components/schemas/$PROBLEM")))),
            )
        }
    }

    @Bean
    fun polymorphicSchemas(): OpenApiCustomizer {
        val roots = PolymorphicSchemas.roots("com.pianonurmi.etrm")
        return OpenApiCustomizer { api -> PolymorphicSchemas.apply(api, roots) }
    }

    companion object {
        const val BEARER = "bearer"
        const val PROBLEM = "Problem"

        private fun stringSchema(): Schema<Any> = Schema<Any>().apply { addType("string") }

        private fun problemSchema(): Schema<Any> = Schema<Any>().apply {
            addType("object")
            addProperty("type", stringSchema())
            addProperty("title", stringSchema())
            addProperty("status", Schema<Any>().apply { addType("integer") })
            addProperty("detail", stringSchema())
            addProperty("instance", stringSchema())
            addProperty("violations", Schema<Any>().apply { addType("array") }.items(Schema<Any>().`$ref`("#/components/schemas/Violation")))
            required = listOf("type", "title", "status")
        }

        private fun violationSchema(): Schema<Any> = Schema<Any>().apply {
            addType("object")
            addProperty("field", stringSchema())
            addProperty("message", stringSchema())
            required = listOf("field", "message")
        }

        /** `TradeController.approve` becomes `trade_approve`, `EtsComplianceController.register` becomes `etsCompliance_register`. */
        fun operationId(controller: String, method: String): String =
            controller.removeSuffix("Controller").replaceFirstChar { it.lowercaseChar() } + "_" + method
    }
}
