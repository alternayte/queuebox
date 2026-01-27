plugins {
    base
    jacoco
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

// Configure after all projects are evaluated
gradle.projectsEvaluated {
    jacocoAggregatedReport.configure {
        val execFiles = subprojects.mapNotNull { subproject ->
            val execFile = subproject.layout.buildDirectory.file("jacoco/test.exec").get().asFile
            execFile.takeIf { it.exists() }
        }

        val sourceDirs = subprojects.mapNotNull { subproject ->
            val srcDir = file("${subproject.projectDir}/src/main/kotlin")
            srcDir.takeIf { it.exists() }
        }

        // Collect class directories, excluding duplicate classes (MainKt exists in multiple modules)
        val classDirs = subprojects.mapNotNull { subproject ->
            val classDir = subproject.layout.buildDirectory.dir("classes/kotlin/main").get().asFile
            if (classDir.exists()) {
                fileTree(classDir) {
                    exclude("**/MainKt.class")
                }
            } else null
        }

        executionData.setFrom(files(execFiles))
        sourceDirectories.setFrom(files(sourceDirs))
        classDirectories.setFrom(files(classDirs))
    }
}
