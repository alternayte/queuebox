plugins { id("buildsrc.convention.kotlin-jvm") }

dependencies {
    implementation(project(":core"))
    implementation(project(":config"))
    implementation(libs.bundles.kotlinxEcosystem)
    implementation(libs.slf4j.api)
    implementation(libs.postgresql)
    implementation(libs.mssql.jdbc)
    val debeziumVersion = "3.4.3.Final"
    implementation("io.debezium:debezium-api:$debeziumVersion")
    implementation("io.debezium:debezium-embedded:$debeziumVersion")
    implementation("io.debezium:debezium-connector-postgres:$debeziumVersion")
    implementation("io.debezium:debezium-connector-sqlserver:$debeziumVersion")
    implementation("io.debezium:debezium-storage-file:$debeziumVersion")
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.mssqlserver)
    testImplementation(libs.testcontainers.junit)
}

dependencies {
    testImplementation(project(":postgres"))
    testImplementation(project(":sqlserver"))
    testImplementation(project(":outbox-service"))
    testImplementation(libs.exposed.core)
    testImplementation(libs.exposed.jdbc)
}
