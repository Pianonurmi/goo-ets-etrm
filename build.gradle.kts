plugins {
    id("etrm.root-conventions")
}

allprojects {
    group = "com.pianonurmi.etrm"
    version = "0.1.0-SNAPSHOT"
}

dependencies {
    subprojects.filter { it.path != ":apps" && it.path != ":modules" }.forEach { kover(it) }
}
