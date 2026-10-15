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
    implementation(project(":modules:document"))
    implementation(libs.springdoc.openapi)
    implementation(libs.shedlock.spring)

    testImplementation(testFixtures(project(":platform")))
    testFixturesImplementation(project(":platform"))
    testFixturesImplementation(project(":modules:referencedata"))
    testFixturesImplementation(project(":modules:product"))
    testFixturesImplementation(project(":modules:marketdata"))
    testFixturesImplementation(testFixtures(project(":platform")))
    testFixturesApi(testFixtures(project(":modules:product")))
    testFixturesImplementation(platform(libs.spring.boot.bom))
    testFixturesImplementation(libs.spring.context)
    testFixturesImplementation(libs.jooq.core)
    testImplementation(project(":modules:taxonomy"))
    testImplementation(testFixtures(project(":modules:referencedata")))
}
