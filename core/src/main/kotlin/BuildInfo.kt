package org.nxtspec

import java.util.Properties

/**
 * The build information that the build generates.
 *
 * F-053: the Gradle build writes `queuebox-build.properties` into the resources of the core
 * module. The version therefore comes from one place, and a release cannot report a stale
 * literal.
 */
object BuildInfo {

    /** The value that this object reports when the resource is absent. */
    const val UNKNOWN = "unknown"

    private const val RESOURCE = "queuebox-build.properties"

    /** The Gradle project version of this build. */
    val version: String = readVersion()

    private fun readVersion(): String {
        val stream = BuildInfo::class.java.classLoader.getResourceAsStream(RESOURCE)
            ?: return UNKNOWN
        return stream.use { input ->
            val properties = Properties()
            properties.load(input)
            properties.getProperty("version")?.takeIf { it.isNotBlank() } ?: UNKNOWN
        }
    }
}
