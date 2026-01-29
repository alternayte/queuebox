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
    implementation(project(":core"))
    implementation(project(":config"))
    implementation(project(":inbox-service"))  // For InboxTransformPipeline

    // RabbitMQ client
    implementation("com.rabbitmq:amqp-client:5.22.0")

    // Kotlinx ecosystem (coroutines, serialization, datetime)
    implementation(libs.bundles.kotlinxEcosystem)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers.rabbitmq)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(project(":postgres"))
    testImplementation("com.zaxxer:HikariCP:6.0.0")
    testImplementation("org.postgresql:postgresql:42.7.4")
    testImplementation("org.jetbrains.exposed:exposed-core:0.56.0")
    testImplementation("org.jetbrains.exposed:exposed-jdbc:0.56.0")
    testImplementation("org.jetbrains.exposed:exposed-json:0.56.0")
    testImplementation("org.jetbrains.exposed:exposed-kotlin-datetime:0.56.0")
}