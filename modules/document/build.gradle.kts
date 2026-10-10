plugins {
    id("etrm.module-conventions")
}

dependencies {
    implementation(project(":platform"))
    implementation(libs.springdoc.openapi)
    implementation(libs.openhtmltopdf.pdfbox)
    implementation(libs.jmustache)

    testImplementation(testFixtures(project(":platform")))
}
