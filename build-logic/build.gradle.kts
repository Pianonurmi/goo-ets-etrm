plugins {
    `kotlin-dsl`
}

// The plugins the build applies are locked like the application's dependencies (../build.gradle.kts).
dependencyLocking { lockAllConfigurations() }

tasks.register("resolveAndLockAll") {
    group = "help"
    description = "Resolves every configuration, so --write-locks records them all"
    notCompatibleWithConfigurationCache("Resolves configurations of the project at execution time")
    val resolvable = configurations.matching { it.isCanBeResolved }
    doFirst {
        require(gradle.startParameter.isWriteDependencyLocks) { "Run with --write-locks" }
        resolvable.forEach { it.resolve() }
    }
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.spring.plugin)
    implementation(libs.spring.boot.gradle.plugin)
    implementation(libs.jooq.gradle.plugin)
    implementation(libs.detekt.gradle.plugin)
    implementation(libs.ktlint.gradle.plugin)
    implementation(libs.kover.gradle.plugin)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.postgresql)
    // Makes the version catalog accessible from precompiled script plugins.
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
}
