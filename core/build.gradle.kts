plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
}

group = "org.nxtspec"
version = "unspecified"

repositories {
    mavenCentral()
}

dependencies {
    // Logging facade. The app module supplies the binding.
    implementation(libs.slf4j.api)
    // Carries the mapped diagnostic context across a coroutine dispatch. See F-046.
    implementation(libs.kotlinx.coroutines.slf4j)

    implementation(libs.kotlinxSerialization)
    implementation(libs.kotlinxDatetime)

    // Micrometer for metrics
    implementation(libs.micrometer.core)

    // JSONPath for idempotency key extraction
    implementation(libs.json.path)

    testImplementation(libs.kotlin.test)
    // SLF4J uses a no-operation mapped diagnostic context without a binding, so the logging
    // tests need one.
    testImplementation(libs.logback.classic)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
}

// Eleventh review gate B3. These tests read files that Gradle cannot infer as inputs: documents
// under `docs/`, migration resources and the sources of another module. Without the declaration
// the task stays UP-TO-DATE, and a change that breaks the check passes CI in silence. This is the
// third instance of that shape in this effort, so the rule is now applied wherever a test reads
// outside its own module.
tasks.test {
    inputs.files(
        rootProject.fileTree("docs") { include("**/*.md") },
        rootProject.files(
            "postgres/src/main/kotlin",
            "sqlserver/src/main/kotlin",
            "postgres/src/main/resources/db",
            "sqlserver/src/main/resources/db"
        )
    ).withPathSensitivity(PathSensitivity.RELATIVE)
}
