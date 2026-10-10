plugins {
    id("etrm.application-conventions")
}

dependencies {
    implementation(project(":platform"))
    rootProject.subprojects
        .filter { it.path.startsWith(":modules:") }
        .forEach { implementation(project(it.path)) }

    testImplementation(testFixtures(project(":platform")))
}
