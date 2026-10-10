plugins {
    id("etrm.application-conventions")
}

dependencies {
    implementation(project(":platform"))
    rootProject.subprojects
        .filter { it.path.startsWith(":modules:") }
        .forEach { implementation(project(it.path)) }

    testImplementation(testFixtures(project(":platform")))
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
