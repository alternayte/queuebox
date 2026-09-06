package org.nxtspec

import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.config.SslConfigs
import java.util.Properties

/**
 * Applies the security settings that a source and a destination share.
 *
 * The SASL password reaches the client through the JAAS configuration string, which is the only
 * shape the Kafka client accepts. The value is revealed at the last moment and never logged: the
 * `Secret` type keeps it out of every printed form, and `ErrorSanitizer` covers the messages that
 * the client itself raises.
 */
internal fun applySecurity(
    properties: Properties,
    securityProtocol: String,
    saslMechanism: String?,
    saslUsername: String?,
    saslPassword: Secret?
) {
    properties.setProperty("security.protocol", securityProtocol)
    if (!securityProtocol.startsWith("SASL")) return

    val mechanism = requireNotNull(saslMechanism) { "A SASL protocol needs a mechanism" }
    val username = requireNotNull(saslUsername) { "A SASL protocol needs a username" }
    val password = requireNotNull(saslPassword) { "A SASL protocol needs a password" }

    properties.setProperty(SaslConfigs.SASL_MECHANISM, mechanism)
    val loginModule = if (mechanism.startsWith("SCRAM")) {
        "org.apache.kafka.common.security.scram.ScramLoginModule"
    } else {
        "org.apache.kafka.common.security.plain.PlainLoginModule"
    }
    properties.setProperty(
        SaslConfigs.SASL_JAAS_CONFIG,
        "$loginModule required username=\"$username\" password=\"${password.reveal()}\";"
    )
    if (securityProtocol == "SASL_SSL") {
        properties.setProperty(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, "https")
    }
}
