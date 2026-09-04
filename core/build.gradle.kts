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

    implementation(libs.kotlinxSerialization)
    implementation(libs.kotlinxDatetime)

    // Micrometer for metrics
    implementation(libs.micrometer.core)

    // JSONPath for idempotency key extraction
    implementation("com.jayway.jsonpath:json-path:2.9.0")

    testImplementation(libs.kotlin.test)
    testImplementation(libs.mockk)
}