// The code in this file is a convention plugin - a Gradle mechanism for sharing reusable build logic.
// `buildSrc` is a Gradle-recognized directory and every plugin there will be easily available in the rest of the build.
package buildsrc.convention

import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    // Apply the Kotlin JVM plugin to add support for Kotlin in JVM projects.
    kotlin("jvm")
    jacoco
}

jacoco {
    // 0.8.13 filters the synthetic classes that the Kotlin coroutine compiler generates.
    // An older version reports a suspend function continuation as uncovered.
    toolVersion = "0.8.13"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

// Classes that carry no testable logic, or that only a running process exercises.
// TESTING.md names each exclusion and its reason.
val coverageExclusions = listOf(
    // F-070: an explicit class list, never a suffix pattern. A suffix pattern hid
    // DynamicTables, which carries the column mapping feature.
    "**/OutboxTable.class",
    "**/InboxTable.class",
    "**/SqlServerOutboxTable.class",
    "**/SqlServerInboxTable.class",
    "**/AppKt.class",
    "**/AppKt$*.class"
)

/**
 * The compiled classes of the module, with the excluded classes removed.
 *
 * The default class directories of the JaCoCo tasks are the whole main output, which also holds
 * the processed resources. A report has no reason to read a resource, and reading one makes
 * Gradle report a missing task dependency. The compiled classes alone are the right input.
 */
fun Project.coveredClassDirectories(): FileCollection {
    val mainClasses = extensions.getByType<SourceSetContainer>()
        .named("main").get().output.classesDirs
    return files(mainClasses.map { fileTree(it) { exclude(coverageExclusions) } })
        .builtBy(mainClasses)
}

tasks.jacocoTestReport {
    classDirectories.setFrom(coveredClassDirectories())
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)

    classDirectories.setFrom(coveredClassDirectories())

    violationRules {
        // Per-module line coverage. Decision 3 of section 2A of hardening-doc.md sets it.
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.60".toBigDecimal()
            }
        }
    }
}

// Wire verification into check task
tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

kotlin {
    // F-042: Java 21 is the long term support release. A non-LTS release forces every adopter
    // onto a short-lived JDK.
    jvmToolchain(21)
}

// F-053: the build writes the project version into a resource of the core module. `BuildInfo`
// reads that resource, so the version metric never carries a stale literal.
if (project.name == "core") {
    val buildInfoDir = layout.buildDirectory.dir("generated/buildInfo/resources")

    val generateBuildInfo = tasks.register<WriteProperties>("generateBuildInfo") {
        group = "build"
        description = "Writes the Gradle project version into a resource"
        destinationFile.set(buildInfoDir.map { it.file("queuebox-build.properties") })
        property("version", rootProject.version.toString())
    }

    extensions.getByType<SourceSetContainer>().named("main") {
        // The directory carries a task output, so the builder declares the producing task.
        // Every consumer of the main resources then gets the dependency, not only
        // processResources.
        resources.srcDir(files(buildInfoDir).builtBy(generateBuildInfo))
    }
}

tasks.withType<Test>().configureEach {
    // Configure all test Gradle tasks to use JUnitPlatform.
    useJUnitPlatform()

    // F-053: a test asserts that the version metric tag equals the Gradle project version.
    systemProperty("queuebox.version", rootProject.version.toString())

    // Log information about all test results, not only the failed ones.
    testLogging {
        events(
            TestLogEvent.FAILED,
            TestLogEvent.PASSED,
            TestLogEvent.SKIPPED
        )
    }
}
