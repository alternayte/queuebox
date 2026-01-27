package org.nxtspec.app

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureHealthRoutes(healthManager: HealthManager) {
    routing {
        get("/health") {
            val health = healthManager.check()
            val status = if (health.status == "healthy") HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
            call.respond(status, health)
        }
    }
}
