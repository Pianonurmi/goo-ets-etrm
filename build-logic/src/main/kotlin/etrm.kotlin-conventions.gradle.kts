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
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        allWarningsAsErrors.set(true)
        freeCompilerArgs.addAll(
            "-Xjsr305=strict",
            "-Xconsistent-data-class-copy-visibility",
        )
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
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

    // Security fixes ahead of the Spring Boot BOM; each entry in the catalogue says when it can go.
    listOf(libs.jackson3.bom, libs.jackson2.bom).forEach {
        implementation(platform(it))
        testImplementation(platform(it))
    }
    constraints {
        listOf(libs.tomcat.embed.core, libs.tomcat.embed.el, libs.tomcat.embed.websocket, libs.lz4.java).forEach {
            implementation(it)
            testImplementation(it)
        }
    }
}

val sharedTestDatabase = gradle.sharedServices.registerIfAbsent("sharedTestDatabase", com.pianonurmi.build.SharedTestDatabase::class) {
    maxParallelUsages.set(1)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // All modules reset and migrate the same test database: serialise test tasks across the build.
    usesService(sharedTestDatabase)
    maxHeapSize = "1g"
    systemProperty("junit.jupiter.execution.parallel.enabled", "false")
    // Integration tests pick the database up from the environment (see TestDatabase in platform test fixtures).
    listOf("ETRM_TEST_DB_URL", "ETRM_DB_USER", "ETRM_DB_PASSWORD").forEach { key ->
        System.getenv(key)?.let { environment(key, it) }
    }
    // `./gradlew test -Detrm.benchmark.lots=100000` sizes the allocation benchmark: pass those flags to the test JVM.
    systemProperties(providers.systemPropertiesPrefixedBy("etrm.benchmark.").get())
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
    // detekt's own Kotlin compiler predates JVM 25 targets; analysing as 21 changes nothing for the rules we run.
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
