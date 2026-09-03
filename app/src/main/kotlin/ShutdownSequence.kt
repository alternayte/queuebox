package org.nxtspec.app

/**
 * Runs the shutdown steps in the one correct order. See F-029.
 *
 * The HTTP server stops first, so an in-flight inbox request finishes against a live database
 * and a live connection pool. The background services stop next. The resources close last.
 *
 * A failure in one step does not stop the remaining steps, because a half-closed process holds
 * a database connection and a broker connection.
 */
class ShutdownSequence(
    private val stopServer: suspend () -> Unit,
    private val stopBackgroundServices: suspend () -> Unit,
    private val closeResources: suspend () -> Unit,
    private val log: (String) -> Unit = ::println
) {
    suspend fun run() {
        log("Shutting down QueueBox...")
        step("HTTP server", stopServer)
        step("background services", stopBackgroundServices)
        step("resources", closeResources)
        log("Shutdown complete")
    }

    private suspend fun step(name: String, action: suspend () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            log("Shutdown step '$name' failed: ${e.message}")
        }
    }
}
