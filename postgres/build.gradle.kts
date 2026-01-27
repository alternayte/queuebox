plugins {
    kotlin("jvm")
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

    // PostgreSQL driver
    implementation("org.postgresql:postgresql:42.7.4")

    // Exposed ORM
    implementation("org.jetbrains.exposed:exposed-core:0.56.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.56.0")
    implementation("org.jetbrains.exposed:exposed-json:0.56.0")
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:0.56.0")

    // Kotlinx
    implementation(libs.bundles.kotlinxEcosystem)

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.testcontainers:postgresql:1.20.4")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
}

kotlin {
    jvmToolchain(23)
}

tasks.test {
    useJUnitPlatform()
}
