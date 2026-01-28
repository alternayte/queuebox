plugins {
    // Apply the shared build logic from a convention plugin.
    // The shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")

    // Apply the Application plugin to add support for building an executable JVM application.
    application
}

dependencies {
    // Project "app" depends on project "utils". (Project paths are separated with ":", so ":utils" refers to the top-level "utils" project.)
    implementation(project(":utils"))
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
    implementation("com.zaxxer:HikariCP:6.0.0")

    // Testing (E2E)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.rabbitmq)
    testImplementation(libs.testcontainers.junit)

    // Exposed ORM for E2E database assertions
    testImplementation("org.jetbrains.exposed:exposed-core:0.56.0")
    testImplementation("org.jetbrains.exposed:exposed-jdbc:0.56.0")
    testImplementation("org.jetbrains.exposed:exposed-json:0.56.0")
    testImplementation("org.jetbrains.exposed:exposed-kotlin-datetime:0.56.0")

    // RabbitMQ client for E2E tests
    testImplementation("com.rabbitmq:amqp-client:5.22.0")

    // Ktor CIO engine for HTTP client in E2E tests
    testImplementation("io.ktor:ktor-client-cio:3.4.0")
}

application {
    // Define the Fully Qualified Name for the application main class
    // (Note that Kotlin compiles `App.kt` to a class with FQN `com.example.app.AppKt`.)
    mainClass = "org.nxtspec.app.AppKt"
}
