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

    implementation(project(":core"))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.mockk)

    implementation(libs.bundles.hopliteEcosystem)
    implementation(libs.bundles.kotlinxEcosystem)
}