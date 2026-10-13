plugins {
    id("etrm.module-conventions")
}

dependencies {
    implementation(project(":platform"))
    implementation(project(":modules:referencedata"))
    implementation(project(":modules:product"))
    implementation(project(":modules:inventory"))
    implementation(project(":modules:trade"))
    implementation(project(":modules:registry"))
    implementation(project(":modules:allocation"))
    implementation(project(":modules:settlement"))
    implementation(project(":modules:marketdata"))
    implementation(project(":modules:valuation"))
    implementation(project(":modules:etscompliance"))
    implementation(project(":modules:workflow"))
    implementation(project(":modules:document"))
    implementation(project(":modules:clearing"))
    implementation(libs.springdoc.openapi)

    testImplementation(testFixtures(project(":platform")))
    testImplementation(project(":modules:taxonomy"))
}
