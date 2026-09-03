plugins {
    id("buildsrc.convention.kotlin-jvm")
}

group = "org.nxtspec"
version = "unspecified"

repositories {
    mavenCentral()
}

dependencies {
    // Flyway migrations
    implementation(libs.flyway.core)
    implementation(libs.flyway.sqlserver)

    implementation(project(":core"))
    implementation(project(":config"))

    // Connection pooling
    implementation("com.zaxxer:HikariCP:6.0.0")

    // SQL Server JDBC driver
    implementation("com.microsoft.sqlserver:mssql-jdbc:12.8.1.jre11")

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
    testImplementation(libs.bundles.testContainersMssqlserver)
}
