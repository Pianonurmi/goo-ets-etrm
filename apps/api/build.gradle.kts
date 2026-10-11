plugins {
    id("etrm.application-conventions")
    alias(libs.plugins.cyclonedx)
}

/** `-Petrm.skipWeb` builds the backend without the web UI (faster local loops). */
val skipWeb = providers.gradleProperty("etrm.skipWeb").isPresent

/** The web UI bundle from :apps:web, served from `static/` (ADR 0025). */
val web by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    implementation(project(":platform"))
    rootProject.subprojects
        .filter { it.path.startsWith(":modules:") }
        .forEach { implementation(project(it.path)) }

    // Selected domain events go to Kafka through Spring Modulith's externalisation (off unless configured).
    implementation(libs.spring.boot.starter.kafka)
    implementation(libs.spring.modulith.events.kafka)

    testImplementation(testFixtures(project(":platform")))
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.testcontainers.junit)
    if (!skipWeb) web(project(path = ":apps:web", configuration = "webDist"))
}

tasks.named<ProcessResources>("processResources") {
    if (!skipWeb) {
        from(web) { into("static") }
    }
}

// The committed OpenAPI document the web client is generated from (ADR 0027); OpenApiSnapshotIT checks it.
val openApiSnapshot = layout.projectDirectory.file("openapi/etrm-api.json")

tasks.named<Test>("test") {
    systemProperty("etrm.openapi.snapshot", openApiSnapshot.asFile.absolutePath)
    // Two carrier threads for virtual threads: pinning or a lock held across blocking calls shows up as a hang.
    jvmArgs("-Djdk.virtualThreadScheduler.parallelism=2", "-Djdk.virtualThreadScheduler.maxPoolSize=4")
    inputs.files(openApiSnapshot).withPropertyName("openApiSnapshot")
}

tasks.register<Test>("updateOpenApiSnapshot") {
    description = "Rewrites apps/api/openapi/etrm-api.json from the running application."
    group = "documentation"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("*OpenApiSnapshotIT") }
    systemProperty("etrm.openapi.snapshot", openApiSnapshot.asFile.absolutePath)
    systemProperty("etrm.openapi.write", "true")
    outputs.upToDateWhen { false }
}

// Software bill of materials of what the jar ships (runtime classpath), CycloneDX JSON; CI publishes it.
tasks.named<org.cyclonedx.gradle.CyclonedxDirectTask>("cyclonedxDirectBom") {
    includeConfigs.set(listOf("runtimeClasspath"))
    projectType.set(org.cyclonedx.model.Component.Type.APPLICATION)
    componentName.set("goo-ets-etrm")
    jsonOutput.set(layout.buildDirectory.file("reports/sbom/etrm-backend.cdx.json"))
    xmlOutput.unsetConvention()
}

// The SBOM travels in the jar, where /actuator/sbom serves it (management port in Kubernetes).
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    from(tasks.named("cyclonedxDirectBom")) {
        into("META-INF/sbom")
        rename { "application.cdx.json" }
    }
}
