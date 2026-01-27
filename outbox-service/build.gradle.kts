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
    implementation(project(":postgres"))

    // Kotlinx ecosystem (coroutines, serialization, datetime)
    implementation(libs.bundles.kotlinxEcosystem)

    // Ktor HTTP client
    implementation(libs.bundles.ktorClientEcosystem)

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(23)
}

tasks.test {
    useJUnitPlatform()
}
