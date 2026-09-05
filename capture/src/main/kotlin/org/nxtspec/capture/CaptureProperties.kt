package org.nxtspec.capture

import org.nxtspec.CaptureConfig
import org.nxtspec.DatabaseConfig
import java.nio.file.Path
import java.util.Properties

@Suppress("CyclomaticComplexMethod")
internal fun captureProperties(database: DatabaseConfig, capture: CaptureConfig): Properties {
    val postgres = capture.mode == "postgres-logical"
    val parsed = parsedConnectionProperties(database.url, postgres)
    val connection = capture.connection
    val host = connection.hostname ?: parsed.getProperty(if (postgres) "PGHOST" else "serverName")
    val port = connection.port?.toString() ?: parsed.getProperty(if (postgres) "PGPORT" else "portNumber")
        ?: if (postgres) "5432" else "1433"
    val db = connection.database ?: parsed.getProperty(if (postgres) "PGDBNAME" else "databaseName")
    require(!host.isNullOrBlank() && !db.isNullOrBlank()) {
        "Capture requires hostname and database connection settings"
    }
    require(!host.contains(',')) { "Set a single capture.connection.hostname for multi-host URLs" }
    val schema = capture.schema ?: if (postgres) "public" else "dbo"
    val state = Path.of(capture.stateDirectory).toAbsolutePath()
    return baseProperties(database, capture, postgres, host, port, db, schema, state).apply {
        addConnectorProperties(this, capture, postgres, parsed)
    }
}

@Suppress("NestedBlockDepth")
private fun parsedConnectionProperties(url: String, postgres: Boolean): Properties = if (postgres) {
    org.postgresql.Driver.parseURL(url, Properties())
        ?: error("Cannot parse PostgreSQL connection settings")
} else {
    Properties().apply {
        com.microsoft.sqlserver.jdbc.SQLServerDriver().getPropertyInfo(url, Properties())
            .forEach { property -> property.value?.let { setProperty(property.name, it) } }
    }
}

@Suppress("LongParameterList")
private fun baseProperties(
    database: DatabaseConfig,
    capture: CaptureConfig,
    postgres: Boolean,
    host: String,
    port: String,
    db: String,
    schema: String,
    state: Path
): Properties = Properties().apply {
    setProperty("name", capture.identity)
    setProperty("topic.prefix", capture.identity)
    setProperty(
        "connector.class",
        if (postgres) {
            "io.debezium.connector.postgresql.PostgresConnector"
        } else {
            "io.debezium.connector.sqlserver.SqlServerConnector"
        }
    )
    setProperty("database.hostname", host)
    setProperty("database.port", port)
    setProperty(if (postgres) "database.dbname" else "database.names", db)
    setProperty("database.user", capture.connection.username ?: database.username)
    setProperty("database.password", (capture.connection.password ?: database.password).reveal())
    setProperty("table.include.list", java.util.regex.Pattern.quote("$schema.${database.outboxTableName}"))
    setProperty("offset.storage", "org.apache.kafka.connect.storage.FileOffsetBackingStore")
    setProperty("offset.storage.file.filename", state.resolve("offsets.dat").toString())
    setProperty("offset.flush.interval.ms", "1000")
    setProperty("max.batch.size", "256")
    setProperty("max.queue.size", "1024")
    setProperty("max.queue.size.in.bytes", "8388608")
    setProperty("record.processing.threads", "1")
    setProperty("snapshot.mode", "initial")
    setProperty("errors.max.retries", "0")
    setProperty("heartbeat.interval.ms", "1000")
}

private fun addConnectorProperties(
    properties: Properties,
    capture: CaptureConfig,
    postgres: Boolean,
    parsed: Properties
) {
    if (postgres) {
        properties.setProperty("plugin.name", "pgoutput")
        properties.setProperty("slot.name", capture.slot)
        properties.setProperty("slot.drop.on.stop", "false")
        properties.setProperty("publication.name", capture.publication)
        properties.setProperty("publication.autocreate.mode", "disabled")
        parsed.getProperty("sslmode")?.let { properties.setProperty("database.sslmode", it) }
    } else {
        properties.setProperty("database.encrypt", capture.connection.encrypt.toString())
        properties.setProperty(
            "database.trustServerCertificate",
            capture.connection.trustServerCertificate.toString()
        )
        properties.setProperty("schema.history.internal", "io.debezium.storage.file.history.FileSchemaHistory")
        val historyFile = Path.of(capture.stateDirectory).toAbsolutePath().resolve("history.dat")
        properties.setProperty("schema.history.internal.file.filename", historyFile.toString())
    }
}

/**
 * Binds persisted capture state to the settings that produced it. Secrets stay out of the value.
 * A changed slot, publication, schema, table, host or database makes the old offsets invalid.
 */
internal fun captureFingerprint(properties: Properties): String {
    val keys = listOf(
        "connector.class",
        "database.hostname",
        "database.port",
        "database.dbname",
        "database.names",
        "table.include.list",
        "slot.name",
        "publication.name"
    )
    val text = keys.joinToString("\n") { "$it=${properties.getProperty(it).orEmpty()}" }
    return java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
