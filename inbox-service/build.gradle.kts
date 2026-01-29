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
    implementation(project(":postgres"))
    implementation(project(":outbox-service"))  // For TransformEngine reuse

    // Ktor server
    implementation(libs.bundles.ktorServerEcosystem)
    implementation(libs.ktor.server.double.receive)

    // Kotlinx ecosystem (coroutines, serialization, datetime)
    implementation(libs.bundles.kotlinxEcosystem)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
}
