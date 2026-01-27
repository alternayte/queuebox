package org.nxtspec

interface Publisher {
    suspend fun publish(message: OutboxMessage, destination: Destination): Result<Unit>
    fun supports(destination: Destination): Boolean
}
