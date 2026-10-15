plugins {
    id("etrm.root-conventions")
}

allprojects {
    group = "com.pianonurmi.etrm"
    // The version comes from gradle.properties (docs/operations/releases.md).

    // Every resolved configuration is locked in gradle.lockfile; refresh with
    // ./gradlew resolveAndLockAll --write-locks (docs/operations/supply-chain.md#pinning).
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
}

dependencies {
    // The web UI is TypeScript with its own coverage (Vitest); Kover covers the JVM projects.
    subprojects.filter { it.path !in setOf(":apps", ":modules", ":apps:web") }.forEach { kover(it) }
}
