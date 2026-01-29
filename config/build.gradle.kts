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

    testImplementation(libs.kotlin.test)
    testImplementation(libs.mockk)

    implementation(libs.bundles.hopliteEcosystem)
    implementation(libs.bundles.kotlinxEcosystem)
}