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
    implementation(project(":postgres"))

    // Kotlinx ecosystem (coroutines, serialization, datetime)
    implementation(libs.bundles.kotlinxEcosystem)

    // Ktor HTTP client
    implementation(libs.bundles.ktorClientEcosystem)

    // JSONata for payload transformation
    implementation(libs.jsonata)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}
