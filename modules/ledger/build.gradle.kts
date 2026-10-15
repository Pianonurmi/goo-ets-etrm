plugins {
    id("etrm.module-conventions")
    `java-test-fixtures`
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
    implementation(libs.shedlock.spring)

    testFixturesImplementation(project(":platform"))
    testImplementation(testFixtures(project(":platform")))
    testImplementation(testFixtures(project(":modules:clearing")))
    testImplementation(project(":modules:taxonomy"))
}
