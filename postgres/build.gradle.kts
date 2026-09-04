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
