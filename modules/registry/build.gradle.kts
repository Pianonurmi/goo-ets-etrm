plugins {
    id("etrm.module-conventions")
}

dependencies {
    implementation(project(":platform"))
    implementation(project(":modules:referencedata"))
    implementation(project(":modules:inventory"))
    implementation(project(":modules:product"))
    implementation(project(":modules:workflow"))
    implementation(project(":modules:document"))
    implementation(libs.springdoc.openapi)
    implementation(libs.shedlock.spring)

    testImplementation(testFixtures(project(":platform")))
    testImplementation(project(":modules:taxonomy"))
}
