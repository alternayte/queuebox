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

    // Flyway migrations
    implementation(libs.flyway.core)
    implementation(libs.flyway.sqlserver)

    implementation(project(":core"))
    implementation(project(":config"))

    // Connection pooling
    implementation(libs.hikaricp)

    // SQL Server JDBC driver
    implementation(libs.mssql.jdbc)

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
    testImplementation(libs.bundles.testContainersMssqlserver)
}
