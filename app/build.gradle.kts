plugins {
    // Apply the shared build logic from a convention plugin.
    // The shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")

    // Apply the Application plugin to add support for building an executable JVM application.
    application

    // Serialization plugin for DTOs
    alias(libs.plugins.kotlinPluginSerialization)
}

dependencies {
    // Logging facade and the binding that the application ships.
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)
    runtimeOnly(libs.logstash.encoder)

    implementation(project(":core"))
    implementation(project(":config"))
    implementation(project(":postgres"))
    implementation(project(":rabbitmq"))
    implementation(project(":outbox-service"))
    implementation(project(":inbox-service"))

    implementation(libs.bundles.ktorServerEcosystem)
    implementation(libs.bundles.kotlinxEcosystem)

    // Micrometer metrics
    implementation(libs.micrometer.core)
    implementation(libs.micrometer.prometheus)

    // HikariCP for database connection pooling
    implementation(libs.hikaricp)

    // Testing (E2E)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.logback.classic)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.rabbitmq)
    testImplementation(libs.testcontainers.junit)

    // Exposed ORM for E2E database assertions
    testImplementation(libs.exposed.core)
    testImplementation(libs.exposed.jdbc)
    testImplementation(libs.exposed.json)
    testImplementation(libs.exposed.kotlin.datetime)

    // RabbitMQ client for E2E tests
    testImplementation(libs.amqp.client)

    // Ktor CIO engine for HTTP client in E2E tests
    testImplementation(libs.ktor.client.cio)

    // F-084: `IntegrationDocSqlTest` executes every SQL statement of docs/integration.md against
    // the shipped schema. The SQL Server dialect needs its own container, driver and migration
    // support.
    testImplementation(project(":sqlserver"))
    testImplementation(libs.testcontainers.mssqlserver)
    testImplementation(libs.mssql.jdbc)
    testImplementation(libs.flyway.sqlserver)
}

// F-072 and F-084: several tests assert that a document and the code agree. Gradle does not know
// that a Markdown file is an input of the test task, so an edit to a document alone left the task
// UP-TO-DATE and the samples went unchecked. Declare the documents that the tests read.
tasks.test {
    // Seventh review gate, second finding. The list named eight documents while
    // `DocumentedExamplesTest` walks README.md and EVERY Markdown file under `docs/`, which is 24
    // today. An edit to one of the sixteen undeclared files left this task UP-TO-DATE, so the
    // check that guards the documentation did not re-run. That is the same shape as the coverage
    // gate defect: the check is wired, and the input that must trigger it is invisible to Gradle.
    // The tree covers a document that somebody adds tomorrow as well.
    // Eighth review gate N6. `GuaranteesTest` resolves a test class of another module by reading
    // its SOURCE file, so those sources are inputs too. A renamed test method there left this
    // task UP-TO-DATE and the guarantee check did not re-run.
    //
    // Ninth review gate N5 records the cost: an edit to one unit test in another module re-runs
    // `:app:test`, which starts containers. That is the price of the source fallback, and a
    // correct check that runs too often beats a fast check that misses a defect. A narrower
    // declaration is not possible, because the set of files the test reads depends on the
    // content of README.md.
    inputs.files(
        rootProject.fileTree("docs") { include("**/*.md") },
        rootProject.files(
            "postgres/src/test/kotlin",
            "sqlserver/src/test/kotlin",
            "outbox-service/src/test/kotlin",
            "inbox-service/src/test/kotlin",
            "core/src/test/kotlin",
            "rabbitmq/src/test/kotlin",
            // Ninth review gate N4. `sourceFileOf` walks the whole repository, so every module
            // that holds a test source is an input. `config` was missing.
            "config/src/test/kotlin"
        ),
        rootProject.files(
            "README.md",
            "docker-compose.yml",
            "docker-compose.override.yml",
            "docker-compose.release.yml",
            ".env.example"
        )
    ).withPathSensitivity(PathSensitivity.RELATIVE)
}

application {
    // Define the Fully Qualified Name for the application main class
    // (Note that Kotlin compiles `App.kt` to a class with FQN `com.example.app.AppKt`.)
    mainClass = "org.nxtspec.app.AppKt"
}
