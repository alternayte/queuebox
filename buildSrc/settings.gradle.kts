pluginManagement {
    repositories {
        // Maven Central rate limits GitHub runners with 429 responses. This mirror of Maven Central
        // takes the load first. gradle/verification-metadata.xml checks every artifact, whatever the host.
        // Plugin marker modules stay on the Plugin Portal, because the mirror serves different marker POMs.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") {
            content { excludeModuleByRegex(".*", ".*\\.gradle\\.plugin") }
        }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // A project-level repositories block would bypass the mirror below, so the build rejects one.
    @Suppress("UnstableApiUsage")
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    // Use Maven Central and the Gradle Plugin Portal for resolving dependencies in the shared build logic (`buildSrc`) project.
    @Suppress("UnstableApiUsage")
    repositories {
        // Maven Central rate limits GitHub runners with 429 responses. This mirror of Maven Central
        // takes the load first. gradle/verification-metadata.xml checks every artifact, whatever the host.
        // Plugin marker modules stay on the Plugin Portal, because the mirror serves different marker POMs.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") {
            content { excludeModuleByRegex(".*", ".*\\.gradle\\.plugin") }
        }
        mavenCentral()
        // F-066: the ktlint Gradle plugin publishes only to the Gradle Plugin Portal.
        gradlePluginPortal()
    }

    // Reuse the version catalog from the main build.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "buildSrc"