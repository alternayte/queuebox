package org.nxtspec.app

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

fun Application.configureMetricsRoutes(registry: PrometheusMeterRegistry) {
    routing {
        get("/metrics") {
            call.respondText(
                registry.scrape(),
                ContentType.parse("text/plain; version=0.0.4; charset=utf-8")
            )
        }
    }
}
