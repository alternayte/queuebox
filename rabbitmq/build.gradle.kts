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

    implementation(project(":core"))
    implementation(project(":config"))
    implementation(project(":inbox-service")) // For InboxTransformPipeline

    // RabbitMQ client
    implementation(libs.amqp.client)

    // Kotlinx ecosystem (coroutines, serialization, datetime)
    implementation(libs.bundles.kotlinxEcosystem)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers.rabbitmq)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(project(":postgres"))
    testImplementation(libs.hikaricp)
    testImplementation(libs.postgresql)
    testImplementation(libs.exposed.core)
    testImplementation(libs.exposed.jdbc)
    testImplementation(libs.exposed.json)
    testImplementation(libs.exposed.kotlin.datetime)
}
