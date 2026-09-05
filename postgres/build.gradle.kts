plugins {
    id("buildsrc.convention.kotlin-jvm")
}

group = "org.nxtspec"
version = "unspecified"

repositories {
    mavenCentral()
}

dependencies {
    // Logging facade. The app module supplies the binding.
    implementation(libs.slf4j.api)

    implementation(project(":core"))
    implementation(project(":config"))

    // Connection pooling
    implementation(libs.hikaricp)

    // Micrometer for HikariCP metrics
    implementation(libs.micrometer.core)

    // PostgreSQL driver
    implementation(libs.postgresql)

    // Flyway migrations
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)

    // Exposed ORM
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.json)
    implementation(libs.exposed.kotlin.datetime)

    // Kotlinx
    implementation(libs.bundles.kotlinxEcosystem)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
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
            "postgres/src/main/resources/db",
            "sqlserver/src/main/resources/db"
        )
    ).withPathSensitivity(PathSensitivity.RELATIVE)
}
