plugins {
    id("etrm.module-conventions")
}

dependencies {
    implementation(project(":platform"))
    implementation(project(":modules:trade"))
    implementation(project(":modules:allocation"))
    implementation(libs.springdoc.openapi)

    testImplementation(testFixtures(project(":platform")))
}
