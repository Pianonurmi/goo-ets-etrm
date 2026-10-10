plugins {
    id("etrm.module-conventions")
}

dependencies {
    implementation(project(":platform"))
    implementation(project(":modules:taxonomy"))
    implementation(libs.springdoc.openapi)

    testImplementation(testFixtures(project(":platform")))
    testImplementation(libs.kotlinx.coroutines.core)
}
