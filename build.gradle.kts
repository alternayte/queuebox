plugins {
    base
    jacoco
    // F-043: the software bill of materials that a release publishes.
    alias(libs.plugins.cyclonedx)
}

// The CycloneDX plugin needs a coordinate for the root component of the bill of materials.
group = "org.nxtspec"

/**
 * F-063: the version comes from the Git tag.
 *
 * The order of precedence is:
 * 1. The `queueboxVersion` Gradle property. A release workflow can set it.
 * 2. The nearest Git tag that starts with `v`. The tag `v1.2.3` gives the version `1.2.3`.
 *    A commit after the tag gives `1.2.3-<count>-g<hash>`. A dirty tree adds `-SNAPSHOT`.
 * 3. `0.0.0-SNAPSHOT` for an untagged build, or when Git is not available.
 *
 * QueueBox ships as a container image only. The build publishes no Maven artifact, so this
 * version names the image and the bill of materials.
 */
fun gitVersion(): String {
    // `providers.exec` runs the command through the configuration cache, so the build stays
    // cacheable. A plain ProcessBuilder at configuration time breaks the configuration cache.
    val result = providers.exec {
        workingDir = rootDir
        commandLine("git", "describe", "--tags", "--match", "v*", "--dirty=-SNAPSHOT", "--always")
        isIgnoreExitValue = true
    }
    if (result.result.get().exitValue != 0) return "0.0.0-SNAPSHOT"
    val described = result.standardOutput.asText.get().trim()

    // `git describe --always` falls back to a bare commit hash when no tag matches.
    if (!described.startsWith("v")) return "0.0.0-SNAPSHOT"
    return described.removePrefix("v")
}

version = (findProperty("queueboxVersion") as String?) ?: gitVersion()

/**
 * F-043: publishes the software bill of materials under the released name.
 *
 * The CycloneDX plugin writes `build/reports/bom.json`. This task copies it to the name that a
 * release publishes. The plugin does not support the configuration cache, so the command is
 * `./gradlew sbom --no-configuration-cache`.
 */
val sbom by tasks.registering(Copy::class) {
    group = "documentation"
    description = "Builds the software bill of materials under the released name"
    dependsOn("cyclonedxBom")

    from(layout.buildDirectory.file("reports/bom.json"))
    into(layout.buildDirectory.dir("reports"))
    rename { "queuebox-${project.version}-sbom.json" }
}


// Aggregated JaCoCo report - collect coverage from all subprojects
val jacocoAggregatedReport by tasks.registering(JacocoReport::class) {
    group = "verification"
    description = "Generates aggregated code coverage report for all subprojects"

    // Depend on all subproject test tasks
    subprojects.forEach { subproject ->
        dependsOn("${subproject.path}:test")
    }

    reports {
        xml.required = true
        html.required = true
        html.outputLocation = layout.buildDirectory.dir("reports/jacoco/aggregated/html")
        xml.outputLocation = layout.buildDirectory.file("reports/jacoco/aggregated/jacocoAggregatedReport.xml")
    }
}

// Aggregated JaCoCo verification - enforce coverage thresholds across all subprojects
val jacocoAggregatedVerification by tasks.registering(JacocoCoverageVerification::class) {
    group = "verification"
    description = "Verifies aggregated code coverage meets minimum thresholds"
    dependsOn(jacocoAggregatedReport)

    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
        }
        rule {
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.70".toBigDecimal()
            }
        }
    }
}

// Convenience task to run coverage verification
/**
 * The aggregated coverage gate runs as part of `check`.
 *
 * Sixth review gate, defect B3. `README.md` and `TESTING.md` both state that `check` fails below
 * the aggregate thresholds. Nothing wired the task in, so the aggregate gate ran only when
 * somebody named `checkCoverage` by hand, and no workflow did. The per-module gate was wired, so
 * the weaker half of the claim was true and the stronger half was not.
 */
tasks.named("check") {
    dependsOn(jacocoAggregatedVerification)
}

val checkCoverage by tasks.registering {
    group = "verification"
    description = "Runs all tests and verifies coverage thresholds"
    dependsOn(jacocoAggregatedVerification)
}

// Convenience task to run all tests with coverage report
val testWithCoverage by tasks.registering {
    group = "verification"
    description = "Runs all tests and generates coverage report"
    dependsOn(jacocoAggregatedReport)
}

/**
 * The inputs of the aggregated coverage tasks, resolved LAZILY.
 *
 * Seventh review gate, defect 1. The earlier version read `execFile.exists()` inside
 * `gradle.projectsEvaluated`, which runs at CONFIGURATION time, before any test has run. So the
 * list held the exec files of the PREVIOUS build. On a warm tree that looks correct. On the first
 * build of a clean checkout the list is empty, JaCoCo skips a report with no execution data, and
 * the gate passes without measuring anything. Every CI job runs exactly one build on a clean
 * checkout, so the gate was vacuous in the only place it mattered.
 *
 * `FileCollection.filter` is lazy: it resolves when the task reads it, which is after the test
 * tasks that this task depends on have run.
 */
val aggregatedExecutionData: FileCollection =
    files(subprojects.map { it.layout.buildDirectory.file("jacoco/test.exec") }).filter { it.exists() }

val aggregatedSourceDirectories: FileCollection =
    files(subprojects.map { "${it.projectDir}/src/main/kotlin" }).filter { it.exists() }

val aggregatedClassDirectories: FileCollection =
    files(
        subprojects.map { subproject ->
            subproject.layout.buildDirectory.dir("classes/kotlin/main").map { directory ->
                fileTree(directory) {
                    // F-070: the exclusions name a class, not a suffix. A suffix pattern hid
                    // DynamicTables, which carries the column mapping feature. TESTING.md names
                    // every entry and its reason.
                    exclude("**/OutboxTable.class")
                    exclude("**/InboxTable.class")
                    exclude("**/SqlServerOutboxTable.class")
                    exclude("**/SqlServerInboxTable.class")
                    exclude("**/AppKt.class")
                    exclude("**/AppKt$*.class")
                }
            }
        }
    )

jacocoAggregatedReport.configure {
    executionData.setFrom(aggregatedExecutionData)
    sourceDirectories.setFrom(aggregatedSourceDirectories)
    classDirectories.setFrom(aggregatedClassDirectories)
}

jacocoAggregatedVerification.configure {
    executionData.setFrom(aggregatedExecutionData)
    sourceDirectories.setFrom(aggregatedSourceDirectories)
    classDirectories.setFrom(aggregatedClassDirectories)
}
