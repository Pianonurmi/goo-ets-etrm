plugins {
    id("etrm.module-conventions")
    `java-test-fixtures`
}

dependencies {
    implementation(project(":platform"))
    implementation(project(":modules:taxonomy"))
    implementation(libs.springdoc.openapi)

    testImplementation(testFixtures(project(":platform")))
    testFixturesImplementation(project(":platform"))
    testFixturesImplementation(project(":modules:taxonomy"))
    testFixturesImplementation(libs.jooq.core)
    testFixturesImplementation(platform(libs.spring.boot.bom))
    testFixturesImplementation(libs.spring.context)
    testImplementation(libs.kotlinx.coroutines.core)
}
