rootProject.name = "goo-ets-etrm"

pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include("platform")
include("apps:api")

listOf(
    "referencedata",
    "taxonomy",
    "product",
    "trade",
    "inventory",
    "allocation",
    "registry",
    "position",
    "ledger",
    "workflow",
    "audit",
    "document",
    "settlement",
    "marketdata",
    "valuation",
).forEach { module ->
    include("modules:$module")
}
