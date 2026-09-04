plugins {
    base
    jacoco
    // F-043: the software bill of materials that a release publishes.
    alias(libs.plugins.cyclonedx)
}

// The CycloneDX plugin needs a coordinate for the root component of the bill of materials.
group = "org.nxtspec"
version = (findProperty("queueboxVersion") as String?) ?: "0.1.0-SNAPSHOT"

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

// Configure after all projects are evaluated
gradle.projectsEvaluated {
    val execFiles = subprojects.mapNotNull { subproject ->
        val execFile = subproject.layout.buildDirectory.file("jacoco/test.exec").get().asFile
        execFile.takeIf { it.exists() }
    }

    val sourceDirs = subprojects.mapNotNull { subproject ->
        val srcDir = file("${subproject.projectDir}/src/main/kotlin")
        srcDir.takeIf { it.exists() }
    }

    // Collect class directories
    val classDirs = subprojects.mapNotNull { subproject ->
        val classDir = subproject.layout.buildDirectory.dir("classes/kotlin/main").get().asFile
        if (classDir.exists()) {
            fileTree(classDir) {
                // See TESTING.md for the reason behind every exclusion.
                exclude("**/*Table.class")
                exclude("**/*Tables.class")
                exclude("**/AppKt.class")
                exclude("**/AppKt$*.class")
            }
        } else null
    }

    jacocoAggregatedReport.configure {
        executionData.setFrom(files(execFiles))
        sourceDirectories.setFrom(files(sourceDirs))
        classDirectories.setFrom(files(classDirs))
    }

    jacocoAggregatedVerification.configure {
        executionData.setFrom(files(execFiles))
        sourceDirectories.setFrom(files(sourceDirs))
        classDirectories.setFrom(files(classDirs))
    }
}
