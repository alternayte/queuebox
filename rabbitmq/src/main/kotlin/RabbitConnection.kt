package org.nxtspec

import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

open class RabbitConnection(private val url: String) {
    private val factory = ConnectionFactory().apply {
        setUri(url)
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
