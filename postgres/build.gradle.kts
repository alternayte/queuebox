plugins {
    id("buildsrc.convention.kotlin-jvm")
}

group = "org.nxtspec"
version = "unspecified"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core"))
    implementation(project(":config"))

    // Connection pooling
    implementation("com.zaxxer:HikariCP:6.0.0")

    // Micrometer for HikariCP metrics
    implementation(libs.micrometer.core)

    // PostgreSQL driver
    implementation("org.postgresql:postgresql:42.7.4")

    // Flyway migrations
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)

    // Exposed ORM
    implementation("org.jetbrains.exposed:exposed-core:0.56.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.56.0")
    implementation("org.jetbrains.exposed:exposed-json:0.56.0")
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:0.56.0")

    // Kotlinx
    implementation(libs.bundles.kotlinxEcosystem)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
}
