plugins {
    id("etrm.module-conventions")
}

dependencies {
    implementation(project(":platform"))
    implementation(project(":modules:referencedata"))
    implementation(project(":modules:product"))
    implementation(project(":modules:inventory"))
    implementation(project(":modules:trade"))
    implementation(project(":modules:allocation"))
    implementation(project(":modules:etscompliance"))
    implementation(libs.springdoc.openapi)
    implementation(libs.shedlock.spring)

    testImplementation(testFixtures(project(":platform")))
}
