plugins {
    id("etrm.module-conventions")
}

dependencies {
    implementation(project(":platform"))
    implementation(project(":modules:taxonomy"))
    implementation(libs.springdoc.openapi)
    implementation(libs.json.schema.validator)

    testImplementation(testFixtures(project(":platform")))
}
