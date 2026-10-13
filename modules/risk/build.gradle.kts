plugins {
    id("etrm.module-conventions")
}

dependencies {
    implementation(project(":platform"))
    implementation(project(":modules:referencedata"))
    implementation(project(":modules:product"))
    implementation(project(":modules:inventory"))
    implementation(project(":modules:trade"))
    implementation(project(":modules:marketdata"))
    implementation(project(":modules:valuation"))
    implementation(project(":modules:settlement"))
    implementation(project(":modules:position"))
    implementation(project(":modules:workflow"))
    implementation(project(":modules:allocation"))
    implementation(project(":modules:clearing"))
    implementation(libs.springdoc.openapi)
    implementation(libs.shedlock.spring)

    testImplementation(testFixtures(project(":platform")))
}
