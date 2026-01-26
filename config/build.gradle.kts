plugins {
    kotlin("jvm")
}

group = "org.nxtspec"
version = "unspecified"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))

    implementation(libs.bundles.hopliteEcosystem)
    implementation(libs.bundles.kotlinxEcosystem)
}

kotlin {
    jvmToolchain(23)
}

tasks.test {
    useJUnitPlatform()
}