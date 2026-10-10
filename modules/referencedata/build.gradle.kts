plugins {
    id("etrm.module-conventions")
}

dependencies {
    implementation(project(":platform"))
    implementation(project(":modules:taxonomy"))
    implementation(project(":modules:workflow"))
    implementation(libs.springdoc.openapi)

    testImplementation(testFixtures(project(":platform")))
}
