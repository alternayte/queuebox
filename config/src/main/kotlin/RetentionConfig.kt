package org.nxtspec

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Serializable
enum class RetentionPolicy {
    @SerialName("age") AGE,
    @SerialName("count") COUNT,
    @SerialName("disabled") DISABLED
}

@Serializable
data class TableRetentionConfig(
    val policy: RetentionPolicy = RetentionPolicy.DISABLED,
    val maxAge: String? = null,
    val maxCount: Int? = null,
    val cleanupInterval: String = "1h",
    val batchSize: Int = 1000
)

@Serializable
data class RetentionConfig(
    val enabled: Boolean = false,
    val outbox: TableRetentionConfig = TableRetentionConfig(),
    val inbox: TableRetentionConfig = TableRetentionConfig()
)

object DurationParser {
    /**
     * Parses a duration string like "7d", "24h", "30m", "60s" into a kotlin.time.Duration.
     *
     * Supported suffixes:
     * - 's' for seconds
     * - 'm' for minutes
     * - 'h' for hours
     * - 'd' for days
     *
     * @param duration The duration string to parse
     * @return The parsed Duration
     * @throws IllegalArgumentException if the format is invalid
     */
    fun parse(duration: String): Duration {
        require(duration.length >= 2) {
            "Invalid duration format: '$duration'. Expected format like '7d', '24h', '30m', '60s'"
        }

        val value = duration.dropLast(1).toLongOrNull()
            ?: throw IllegalArgumentException(
                "Invalid duration format: '$duration'. The numeric part '${duration.dropLast(1)}' is not a valid number"
            )

        require(value >= 0) {
            "Invalid duration format: '$duration'. Duration value must be non-negative"
        }

        return when (duration.last()) {
            's' -> value.seconds
            'm' -> value.minutes
            'h' -> value.hours
            'd' -> value.days
            else -> throw IllegalArgumentException(
                "Invalid duration format: '$duration'. Supported suffixes: " +
                    "'s' (seconds), 'm' (minutes), 'h' (hours), 'd' (days)"
            )
        }
    }
}
