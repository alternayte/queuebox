package org.nxtspec

import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Thrown when the AMQP URI of a source or a destination cannot be parsed.
 *
 * It carries NO cause and it never names the URI. `URISyntaxException` embeds the whole value it
 * rejected, and a broker URI holds the password. Five review gates repaired the redaction of that
 * text after the fact; this stops the credential entering the text at all. The redaction stays as
 * the second layer, not the first.
 */
class InvalidAmqpUriException(message: String) : RuntimeException(message)

open class RabbitConnection(private val url: String) {
    private val factory = ConnectionFactory().apply {
        try {
            setUri(url)
        } catch (e: Exception) {
            throw InvalidAmqpUriException(
                "The AMQP URI is not valid: ${CredentialMasking.maskUrl(e.message ?: "no reason")}"
            )
        }
        isAutomaticRecoveryEnabled = true
        networkRecoveryInterval = 5000 // 5 seconds
    }

    private var connection: Connection? = null
    private val connectionLock = Mutex()

    open suspend fun getChannel(): Channel = connectionLock.withLock {
        if (connection == null || !connection!!.isOpen) {
            connection = withContext(Dispatchers.IO) {
                factory.newConnection()
            }
        }
        withContext(Dispatchers.IO) {
            connection!!.createChannel()
        }
    }

    suspend fun close() {
        connectionLock.withLock {
            connection?.close()
            connection = null
        }
    }
}
