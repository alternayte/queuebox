package org.nxtspec.app.dto

import kotlinx.serialization.Serializable

/**
 * Error body for a refused replay request. See F-096.
 */
@Serializable
data class ReplayErrorResponse(val error: String)
