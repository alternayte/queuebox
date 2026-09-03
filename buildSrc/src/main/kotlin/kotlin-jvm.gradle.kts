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
    "**/*Table.class",
    "**/*Tables.class",
    "**/AppKt.class",
    "**/AppKt$*.class"
)

tasks.jacocoTestReport {
    classDirectories.setFrom(
        files(classDirectories.files.map { fileTree(it) { exclude(coverageExclusions) } })
    )
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)

    classDirectories.setFrom(
        files(classDirectories.files.map { fileTree(it) { exclude(coverageExclusions) } })
    )

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
    // Use a specific Java version to make it easier to work in different environments.
    jvmToolchain(23)
}

tasks.withType<Test>().configureEach {
    // Configure all test Gradle tasks to use JUnitPlatform.
    useJUnitPlatform()

    // Log information about all test results, not only the failed ones.
    testLogging {
        events(
            TestLogEvent.FAILED,
            TestLogEvent.PASSED,
            TestLogEvent.SKIPPED
        )
    }
}
