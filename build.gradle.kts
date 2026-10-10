plugins {
    id("etrm.root-conventions")
}

allprojects {
    group = "com.pianonurmi.etrm"
    version = "0.1.0-SNAPSHOT"
}

dependencies {
    // The web UI is TypeScript with its own coverage (Vitest); Kover covers the JVM projects.
    subprojects.filter { it.path !in setOf(":apps", ":modules", ":apps:web") }.forEach { kover(it) }
}
