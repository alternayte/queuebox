package org.nxtspec

/** F-097: raised when the consumer cannot start consuming from its configured source queue. */
class RabbitConsumeException(message: String, cause: Throwable? = null) : Exception(message, cause)
