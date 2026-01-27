package org.nxtspec.http

class HttpPublishException(
    message: String,
    val statusCode: Int? = null,
    val body: String? = null,
    cause: Throwable? = null
) : Exception(message, cause)
