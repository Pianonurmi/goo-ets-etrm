// Web UI (ADR 0025): npm drives the build; Gradle wires it into `check` and `assemble` and hands the bundle to
// :apps:api, which serves it from `static/`. Node.js 22 and npm must be on the PATH.
plugins {
    base
}

val npm = if (System.getProperty("os.name").lowercase().contains("windows")) "npm.cmd" else "npm"
val openApiSnapshot = rootProject.layout.projectDirectory.file("apps/api/openapi/etrm-api.json")
val sources = files(
    "src",
    "scripts",
    "index.html",
    "public",
    "package.json",
    "tsconfig.json",
    "vite.config.ts",
    "vite.help.ts",
    rootProject.file("docs/user-guide"),
    rootProject.file("docs/images"),
    "eslint.config.js",
    ".prettierrc.json",
)

fun Exec.npmScript(vararg args: String) {
    workingDir = projectDir
    commandLine(npm, *args)
}

val npmCi = tasks.register<Exec>("npmCi") {
    description = "Installs the web dependencies from package-lock.json"
    group = "web"
    inputs.files("package.json", "package-lock.json")
    outputs.file("node_modules/.package-lock.json")
    npmScript("ci", "--no-audit", "--no-fund")
}

val genApi = tasks.register<Exec>("genApi") {
    description = "Generates the TypeScript API types from the committed OpenAPI snapshot (ADR 0027)"
    group = "web"
    dependsOn(npmCi)
    inputs.file(openApiSnapshot)
    outputs.file("src/shared/api/schema.d.ts")
    outputs.file("src/shared/auth/permissions.generated.ts")
    npmScript("run", "gen:api")
}

/** A check task whose outcome is a stamp file, so it is skipped when nothing it reads changed. */
fun registerCheck(name: String, script: String, description: String) = tasks.register<Exec>(name) {
    this.description = description
    group = "verification"
    dependsOn(genApi)
    inputs.files(sources)
    inputs.files("e2e", "playwright.config.ts").optional()
    val stamp = layout.buildDirectory.file("checks/$name.stamp")
    outputs.file(stamp)
    npmScript("run", script)
    doLast { stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n") }
}

val lintWeb = registerCheck("lintWeb", "lint", "ESLint over the web sources")
val formatWeb = registerCheck("formatCheckWeb", "format:check", "Prettier check over the web sources")
val typecheckWeb = registerCheck("typecheckWeb", "typecheck", "TypeScript type check")
val testWeb = registerCheck("testWeb", "test:coverage", "Vitest unit and component tests")
val apiCoverage = registerCheck("apiCoverageWeb", "coverage:api", "Every API operation has a UI caller or a reason in api-only.json")
val helpCheck = registerCheck("helpWeb", "check:help", "The manual's pages, links, anchors and screenshots")
apiCoverage.configure {
    inputs.file(openApiSnapshot)
    inputs.file("api-only.json")
}

val viteBuild = tasks.register<Exec>("viteBuild") {
    description = "Builds the production bundle into build/dist"
    group = "build"
    dependsOn(genApi)
    inputs.files(sources)
    outputs.dir("build/dist")
    npmScript("run", "build")
}

val bundleBudget = tasks.register<Exec>("bundleBudget") {
    description = "Fails when the gzip size of the first page, any chunk or the whole bundle exceeds its budget"
    group = "verification"
    dependsOn(viteBuild)
    inputs.dir("build/dist")
    inputs.file("scripts/check-bundle.mjs")
    val stamp = layout.buildDirectory.file("checks/bundleBudget.stamp")
    outputs.file(stamp)
    npmScript("run", "budget")
    doLast { stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n") }
}

tasks.named("check") { dependsOn(lintWeb, formatWeb, typecheckWeb, testWeb, apiCoverage, helpCheck, bundleBudget) }
tasks.named("assemble") { dependsOn(viteBuild) }

/** The bundle, consumed by :apps:api. */
val webDist = configurations.create("webDist") {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(webDist.name, layout.buildDirectory.dir("dist")) { builtBy(viteBuild) }
}
