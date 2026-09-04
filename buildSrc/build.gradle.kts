plugins {
    // The Kotlin DSL plugin provides a convenient way to develop convention plugins.
    // Convention plugins are located in `src/main/kotlin`, with the file extension `.gradle.kts`,
    // and are applied in the project's `build.gradle.kts` files as required.
    `kotlin-dsl`
}

kotlin {
    // F-042: Java 21 is the long term support release.
    jvmToolchain(21)
}

dependencies {
    // Add a dependency on the Kotlin Gradle plugin, so that convention plugins can apply it.
    implementation(libs.kotlinGradlePlugin)

    // F-066: the convention plugin applies ktlint and detekt, so buildSrc needs both plugins
    // on its own classpath.
    implementation(libs.ktlint.gradle.plugin)
    implementation(libs.detekt.gradle.plugin)
}
