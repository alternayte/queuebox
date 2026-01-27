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

    // Ktor server
    implementation(libs.bundles.ktorServerEcosystem)

    // Kotlinx ecosystem (coroutines, serialization, datetime)
    implementation(libs.bundles.kotlinxEcosystem)

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(23)
}

tasks.test {
    useJUnitPlatform()
}
