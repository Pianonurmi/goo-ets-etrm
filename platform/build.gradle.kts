plugins {
    id("etrm.kotlin-conventions")
    `java-test-fixtures`
}

dependencies {
    implementation(platform(libs.spring.modulith.bom))
    implementation(libs.spring.context)
    implementation(libs.spring.tx)
    implementation(libs.spring.modulith.events.api)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.uuid.generator)
    implementation(libs.slf4j.api)
    api(libs.jooq.core)
    api(libs.jooq.postgres.extensions)

    testFixturesImplementation(platform(libs.spring.boot.bom))
    testFixturesApi(libs.flyway.core)
    testFixturesApi(libs.flyway.postgresql)
    testFixturesApi(libs.postgresql)
    testFixturesApi(libs.hikari)
    testFixturesApi(libs.jooq.core)
    testFixturesApi(libs.junit.jupiter)
    testFixturesImplementation(libs.slf4j.api)
    testFixturesImplementation(libs.testcontainers.postgresql)
    testFixturesImplementation(libs.testcontainers.junit)
}
