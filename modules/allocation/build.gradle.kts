plugins {
    id("etrm.module-conventions")
    `java-test-fixtures`
}

dependencies {
    implementation(project(":platform"))
    implementation(project(":modules:workflow"))
    implementation(project(":modules:referencedata"))
    implementation(project(":modules:product"))
    implementation(project(":modules:marketdata"))
    implementation(project(":modules:inventory"))
    implementation(project(":modules:trade"))
    implementation(project(":modules:clearing"))
    implementation(project(":modules:registry"))
    implementation(libs.springdoc.openapi)
    implementation(libs.shedlock.spring)
    // Exact solver (ADR 0031). Natives for Linux and Apple silicon only; the others would add 60 MB to the jar.
    implementation(libs.ortools) {
        exclude(group = "com.google.ortools", module = "ortools-win32-x86-64")
        exclude(group = "com.google.ortools", module = "ortools-darwin-x86-64")
    }

    testImplementation(testFixtures(project(":platform")))
    testImplementation(testFixtures(project(":modules:referencedata")))
    testImplementation(testFixtures(project(":modules:trade")))
    testImplementation(project(":modules:taxonomy"))
}
