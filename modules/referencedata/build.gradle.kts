plugins {
    id("etrm.module-conventions")
    `java-test-fixtures`
}

dependencies {
    implementation(project(":platform"))
    implementation(project(":modules:taxonomy"))
    implementation(project(":modules:workflow"))
    implementation(libs.springdoc.openapi)

    testImplementation(testFixtures(project(":platform")))
    testFixturesImplementation(project(":modules:taxonomy"))
    testFixturesImplementation(platform(libs.spring.boot.bom))
    testFixturesImplementation(libs.spring.context)
    testFixturesImplementation(libs.jooq.core)
}
