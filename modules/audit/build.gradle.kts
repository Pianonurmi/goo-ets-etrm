plugins {
    id("etrm.module-conventions")
}

dependencies {
    implementation(project(":platform"))
    implementation(libs.springdoc.openapi)

    testImplementation(testFixtures(project(":platform")))
}
