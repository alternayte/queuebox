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
    // Carries the mapped diagnostic context across a coroutine dispatch. See F-046.
    implementation(libs.kotlinx.coroutines.slf4j)

    implementation(libs.kotlinxSerialization)
    implementation(libs.kotlinxDatetime)

    // Micrometer for metrics
    implementation(libs.micrometer.core)

    // JSONPath for idempotency key extraction
    implementation(libs.json.path)

    testImplementation(libs.kotlin.test)
    // SLF4J uses a no-operation mapped diagnostic context without a binding, so the logging
    // tests need one.
    testImplementation(libs.logback.classic)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
}