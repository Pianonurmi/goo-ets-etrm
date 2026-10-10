plugins {
    id("etrm.module-conventions")
}

dependencies {
    implementation(project(":platform"))
    implementation(project(":modules:workflow"))
    implementation(project(":modules:referencedata"))
    implementation(project(":modules:product"))
    implementation(project(":modules:document"))
    implementation(libs.springdoc.openapi)

    testImplementation(testFixtures(project(":platform")))
    testImplementation(project(":modules:taxonomy"))
}
