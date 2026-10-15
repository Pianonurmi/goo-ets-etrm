plugins {
    id("etrm.module-conventions")
    `java-test-fixtures`
}

dependencies {
    implementation(project(":platform"))
    implementation(project(":modules:referencedata"))
    implementation(project(":modules:inventory"))
    implementation(project(":modules:product"))
    implementation(project(":modules:workflow"))
    implementation(project(":modules:document"))
    implementation(libs.springdoc.openapi)
    implementation(libs.shedlock.spring)

    testImplementation(testFixtures(project(":platform")))
    testImplementation(project(":modules:taxonomy"))
    testFixturesImplementation(project(":platform"))
    testFixturesImplementation(project(":modules:referencedata"))
    testFixturesImplementation(project(":modules:inventory"))
    testFixturesImplementation(platform(libs.spring.boot.bom))
    testFixturesImplementation(libs.spring.context)
    testFixturesImplementation(libs.jooq.core)
}
