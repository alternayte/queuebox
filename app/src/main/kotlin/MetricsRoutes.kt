package org.nxtspec.app

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureMetricsRoutes(collector: MetricsCollector) {
    routing {
        get("/metrics") {
            call.respondText(collector.toPrometheusFormat(), ContentType.Text.Plain)
        }
    }
}
