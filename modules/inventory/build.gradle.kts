plugins {
    id("etrm.module-conventions")
}

dependencies {
    implementation(project(":platform"))
    implementation(project(":modules:taxonomy"))
    implementation(project(":modules:referencedata"))
    implementation(project(":modules:product"))
    implementation(project(":modules:workflow"))
    implementation(libs.springdoc.openapi)
    implementation(libs.shedlock.spring)

    testImplementation(testFixtures(project(":platform")))
}
