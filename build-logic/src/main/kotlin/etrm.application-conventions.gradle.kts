import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    id("etrm.kotlin-conventions")
    kotlin("plugin.spring")
    id("org.springframework.boot")
}

val libs = the<LibrariesForLibs>()

dependencies {
    implementation(platform(libs.spring.modulith.bom))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.jooq)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.spring.modulith.starter.core)
    implementation(libs.spring.modulith.starter.jdbc)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.springdoc.openapi)
    implementation(libs.shedlock.spring)
    implementation(libs.shedlock.jdbc)
    runtimeOnly(libs.postgresql)

    testImplementation(platform(libs.spring.modulith.bom))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.security.test)
    testImplementation(libs.spring.modulith.starter.test)
    testImplementation(libs.archunit)
    testImplementation(libs.rest.assured)
    testImplementation(libs.rest.assured.kotlin)
    testImplementation(libs.awaitility)
}

springBoot {
    buildInfo()
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("${project.name}.jar")
}
