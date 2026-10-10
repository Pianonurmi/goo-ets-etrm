import io.gitlab.arturbosch.detekt.Detekt
import org.gradle.accessors.dm.LibrariesForLibs
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    id("io.gitlab.arturbosch.detekt")
    id("org.jlleitschuh.gradle.ktlint")
    id("org.jetbrains.kotlinx.kover")
}

val libs = the<LibrariesForLibs>()

group = "com.pianonurmi.etrm"
version = rootProject.version

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        allWarningsAsErrors.set(true)
        freeCompilerArgs.addAll(
            "-Xjsr305=strict",
            "-Xannotation-default-target=param-property",
            "-Xconsistent-data-class-copy-visibility",
        )
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlin.stdlib)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotest.property)
    testImplementation(libs.mockk)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = "1g"
    systemProperty("junit.jupiter.execution.parallel.enabled", "false")
    // Integration tests pick the database up from the environment (see TestDatabase in platform test fixtures).
    listOf("ETRM_TEST_DB_URL", "ETRM_DB_USER", "ETRM_DB_PASSWORD").forEach { key ->
        System.getenv(key)?.let { environment(key, it) }
    }
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    baseline = rootProject.file("config/detekt/baseline.xml").takeIf { it.exists() }
    source.setFrom("src/main/kotlin", "src/test/kotlin")
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = "21"
    reports {
        html.required.set(true)
        xml.required.set(true)
        sarif.required.set(true)
    }
}

ktlint {
    version.set("1.7.1")
    android.set(false)
    filter {
        exclude { it.file.path.contains("/generated") }
    }
}
