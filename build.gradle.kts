plugins {
    id("etrm.root-conventions")
}

allprojects {
    group = "com.pianonurmi.etrm"
    // The version comes from gradle.properties (docs/operations/releases.md).
}

dependencies {
    // The web UI is TypeScript with its own coverage (Vitest); Kover covers the JVM projects.
    subprojects.filter { it.path !in setOf(":apps", ":modules", ":apps:web") }.forEach { kover(it) }
}
