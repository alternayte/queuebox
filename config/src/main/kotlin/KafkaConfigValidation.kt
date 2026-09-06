package org.nxtspec

/**
 * The Kafka rules of the configuration.
 *
 * They live beside `ConfigValidator` rather than inside it, because that class already carries
 * every other rule and detekt reports it as too large. A broker keeps its own file.
 */
/** The smallest delivery timeout that leaves room for a request timeout. */
internal const val MINIMUM_KAFKA_TIMEOUT_MS = 2000L

/** The protocols that the Kafka client accepts. A typo here fails at connect, not at start. */
internal val kafkaSecurityProtocols = setOf("PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL")

internal fun validateKafkaDestination(name: String, dest: DestinationConfig.Kafka) {
    require(dest.bootstrapServers.isNotBlank()) {
        "Destination '$name' needs 'destinations.$name.bootstrapServers'."
    }
    require(dest.topic.isNotBlank()) { "Destination '$name' needs 'destinations.$name.topic'." }
    // The Kafka producer refuses a delivery timeout that leaves no room for the request
    // timeout, and it refuses it when the producer is constructed, which is the first publish.
    // The rule belongs at startup instead.
    require(dest.timeoutMs >= MINIMUM_KAFKA_TIMEOUT_MS) {
        "Destination '$name' timeoutMs must be at least $MINIMUM_KAFKA_TIMEOUT_MS. The Kafka " +
            "producer needs the delivery timeout to stay above the request timeout."
    }
    validateKafkaSecurity(
        "Destination '$name'",
        "destinations.$name",
        dest.securityProtocol,
        dest.saslMechanism,
        dest.saslUsername,
        dest.saslPassword
    )
}

internal fun validateKafkaSource(name: String, source: SourceConfig.Kafka) {
    require(source.bootstrapServers.isNotBlank()) {
        "Source '$name' needs 'sources.$name.bootstrapServers'."
    }
    require(source.topics.isNotEmpty()) { "Source '$name' needs at least one entry in 'sources.$name.topics'." }
    require(source.topics.none { it.isBlank() }) { "Source '$name' has a blank entry in 'sources.$name.topics'." }
    require(source.groupId.isNotBlank()) {
        "Source '$name' needs 'sources.$name.groupId'. Every replica of one deployment shares it, " +
            "so the partitions are shared rather than consumed twice."
    }
    require(source.autoOffsetReset in setOf("earliest", "latest")) {
        "Source '$name' autoOffsetReset must be 'earliest' or 'latest'."
    }
    require(source.maxPollRecords > 0) { "Source '$name' maxPollRecords must be greater than 0." }
    validateKafkaSecurity(
        "Source '$name'",
        "sources.$name",
        source.securityProtocol,
        source.saslMechanism,
        source.saslUsername,
        source.saslPassword
    )
}

@Suppress("LongParameterList")
internal fun validateKafkaSecurity(
    subject: String,
    path: String,
    securityProtocol: String,
    saslMechanism: String?,
    saslUsername: String?,
    saslPassword: Secret?
) {
    require(securityProtocol in kafkaSecurityProtocols) {
        "$subject securityProtocol '$securityProtocol' is not one of $kafkaSecurityProtocols."
    }
    if (securityProtocol.startsWith("SASL")) {
        require(!saslMechanism.isNullOrBlank()) {
            "$subject uses $securityProtocol, so '$path.saslMechanism' must be set."
        }
        require(!saslUsername.isNullOrBlank() && saslPassword != null) {
            "$subject uses $securityProtocol, so '$path.saslUsername' and '$path.saslPassword' must be set."
        }
    }
}
