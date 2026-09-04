package org.nxtspec.app

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Registers the health endpoints.
 *
 * F-049: `/health/live` reports the process only. `/health/ready` reports the dependencies.
 * `/health` stays as an alias of readiness for compatibility.
 */
fun Application.configureHealthRoutes(healthManager: HealthManager) {
    routing {
        get("/health/live") {
            call.respond(HttpStatusCode.OK, healthManager.live())
        }
        get("/health/ready") {
            call.respondReadiness(healthManager)
        }
        get("/health") {
            call.respondReadiness(healthManager)
        }
    }
}

private suspend fun ApplicationCall.respondReadiness(healthManager: HealthManager) {
    val health = healthManager.ready()
    val status = if (health.status == "healthy") HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
    respond(status, health)
}
