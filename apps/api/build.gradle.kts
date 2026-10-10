plugins {
    id("etrm.application-conventions")
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

    testImplementation(testFixtures(project(":platform")))
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
