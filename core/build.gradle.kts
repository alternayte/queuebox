plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinPluginSerialization)
}

group = "org.nxtspec"
version = "unspecified"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.kotlinxSerialization)
    implementation(libs.kotlinxDatetime)

    // JSONPath for idempotency key extraction
    implementation("com.jayway.jsonpath:json-path:2.9.0")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(23)
}

tasks.test {
    useJUnitPlatform()
}