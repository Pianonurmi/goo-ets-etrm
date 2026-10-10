plugins {
    id("etrm.module-conventions")
}

dependencies {
    implementation(project(":platform"))

    testImplementation(testFixtures(project(":platform")))
}
