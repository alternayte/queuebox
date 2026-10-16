package org.nxtspec

import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.QueryBuilder
import kotlin.time.Instant

/** The UTC text of [instant] that SQL Server casts to DATETIME2, with millisecond precision. */
internal fun utcDateTime2(instant: Instant): String = java.time.format.DateTimeFormatter
    .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
    .withZone(java.time.ZoneOffset.UTC)
    .format(java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds()))

/**
 * [instant] as a UTC DATETIME2 literal. `updated_at` holds `SYSUTCDATETIME()`, and an Exposed
 * timestamp parameter binds the JVM wall clock, which differs outside UTC. The text comes from
 * the formatter, so it is safe in the statement.
 */
internal fun utcMoment(instant: Instant): Expression<Instant> = object : Expression<Instant>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append("CAST('${utcDateTime2(instant)}' AS DATETIME2)")
    }
}
