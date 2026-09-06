package org.nxtspec

/**
 * The NATS rules of the configuration.
 *
 * They live beside `ConfigValidator` for the same reason as the Kafka rules: that class already
 * carries every other rule and detekt reports it as too large.
 */

internal fun validateNatsDestination(name: String, dest: DestinationConfig.Nats) {
    require(dest.servers.isNotBlank()) { "Destination '$name' needs 'destinations.$name.servers'." }
    require(dest.subject.isNotBlank()) { "Destination '$name' needs 'destinations.$name.subject'." }
    require(dest.timeoutMs > 0) { "Destination '$name' timeoutMs must be greater than 0." }
    validateNatsCredentials("Destination '$name'", "destinations.$name", dest.username, dest.password, dest.token)
}

internal fun validateNatsSource(name: String, source: SourceConfig.Nats) {
    require(source.servers.isNotBlank()) { "Source '$name' needs 'sources.$name.servers'." }
    require(source.stream.isNotBlank()) {
        "Source '$name' needs 'sources.$name.stream'. QueueBox consumes a JetStream stream and " +
            "never creates one, because the retention and the replication of a stream are an " +
            "operator decision."
    }
    require(source.durable.isNotBlank()) {
        "Source '$name' needs 'sources.$name.durable'. A durable consumer keeps its position " +
            "across a restart, which is what makes the inbox lose nothing."
    }
    require(source.ackWaitMs > 0) { "Source '$name' ackWaitMs must be greater than 0." }
    require(source.batchSize > 0) { "Source '$name' batchSize must be greater than 0." }
    validateNatsCredentials("Source '$name'", "sources.$name", source.username, source.password, source.token)
}

private fun validateNatsCredentials(
    subject: String,
    path: String,
    username: String?,
    password: Secret?,
    token: Secret?
) {
    require(username == null || password != null) {
        "$subject sets '$path.username', so '$path.password' must be set as well."
    }
    require(password == null || username != null) {
        "$subject sets '$path.password', so '$path.username' must be set as well."
    }
    require(token == null || username == null) {
        "$subject sets both a token and a username. NATS accepts one of them, so remove one."
    }
}
