plugins {
    id("etrm.module-conventions")
}

dependencies {
    implementation(project(":platform"))
    implementation(project(":modules:referencedata"))
    implementation(project(":modules:product"))
    implementation(project(":modules:trade"))
    implementation(project(":modules:inventory"))
    implementation(project(":modules:allocation"))
    implementation(project(":modules:marketdata"))
    implementation(project(":modules:valuation"))
    implementation(project(":modules:etscompliance"))
    implementation(project(":modules:risk"))
    implementation(project(":modules:ledger"))
    implementation(libs.springdoc.openapi)

    testImplementation(testFixtures(project(":platform")))
    testImplementation(testFixtures(project(":modules:ledger")))
}
