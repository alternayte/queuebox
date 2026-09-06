package org.nxtspec

import io.nats.client.Connection
import io.nats.client.Nats
import io.nats.client.Options
import java.time.Duration

/**
 * Opens a NATS connection from the settings that a source and a destination share.
 *
 * The credential reaches the client through the options object and never through a URL, so no
 * password can appear in a log line that repeats a server address.
 */
internal fun connect(
    servers: String,
    username: String?,
    password: Secret?,
    token: Secret?,
    connectionTimeoutMs: Long
): Connection {
    val builder = Options.Builder()
        .servers(servers.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray())
        .connectionTimeout(Duration.ofMillis(connectionTimeoutMs))
        // The client reconnects for the life of the process. A source that gave up after a
        // handful of attempts would stop consuming and never say so again.
        .maxReconnects(-1)

    if (username != null && password != null) {
        builder.userInfo(username, password.reveal())
    }
    token?.let { builder.token(it.reveal().toCharArray()) }

    return Nats.connect(builder.build())
}
