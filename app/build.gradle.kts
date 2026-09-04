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
    inputs.files(
        rootProject.files(
            "README.md",
            "docs/integration.md",
            "docs/operations/metrics.md",
            "docs/architecture.md",
            "docs/getting-started.md",
            "docs/configuration.md",
            "docs/transforms.md",
            "docs/message-flow.md",
            "docker-compose.yml",
            "docker-compose.override.yml",
            ".env.example"
        )
    ).withPathSensitivity(PathSensitivity.RELATIVE)
}

application {
    // Define the Fully Qualified Name for the application main class
    // (Note that Kotlin compiles `App.kt` to a class with FQN `com.example.app.AppKt`.)
    mainClass = "org.nxtspec.app.AppKt"
}
