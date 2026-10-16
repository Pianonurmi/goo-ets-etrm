plugins {
    id("etrm.module-conventions")
}

dependencies {
    implementation(project(":platform"))
    implementation(libs.springdoc.openapi)
    implementation(libs.openhtmltopdf.pdfbox)
    implementation(libs.jmustache)
    implementation(libs.shedlock.spring)

    testImplementation(testFixtures(project(":platform")))
    testImplementation(libs.awaitility)
}
