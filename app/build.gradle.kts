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
}

application {
    // Define the Fully Qualified Name for the application main class
    // (Note that Kotlin compiles `App.kt` to a class with FQN `com.example.app.AppKt`.)
    mainClass = "org.nxtspec.app.AppKt"
}
