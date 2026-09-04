package org.nxtspec.http

class HttpPublishException(
    message: String,
    val statusCode: Int? = null,
    val body: String? = null,
    cause: Throwable? = null
) : Exception(message, cause),
    org.nxtspec.SanitizableDetail {
    /** `ErrorSanitizer` reports the response body, after it redacts it. */
    override val detail: String? get() = body
}
