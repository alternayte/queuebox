package org.nxtspec.capture

import io.debezium.engine.ChangeEvent
import io.debezium.engine.DebeziumEngine
import io.debezium.engine.format.Json
import kotlinx.coroutines.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.nxtspec.*
import org.nxtspec.logging.logger
import java.sql.Connection
import javax.sql.DataSource

/** CDC only wakes SQL delivery; acknowledging a hint never deletes an outbox row. */
class OutboxCapture(
    private val database: DatabaseConfig,
    private val config: CaptureConfig,
    private val dataSource: DataSource,
    private val signal: DeliverySignal
) {
    private val log = logger<OutboxCapture>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var engine: DebeziumEngine<ChangeEvent<String, String>>? = null

    @Volatile var healthy: Boolean = false
        private set

    @Volatile var recoveryRequired: Boolean = false
        private set
    private var started = false
    private var bootstrapStateCreated = false

    /**
     * True once an offset file has existed. The bootstrap bypass only covers a first start
     * that failed before the first flush, so a file that appears and then disappears must
     * still demand a recovery.
     */
    @Volatile private var offsetsSeen = false

    @Synchronized fun start() {
        check(!started) { "Capture already started" }
        started = true
        if (!config.enabled) return
        scope.launch {
            var backoff = INITIAL_BACKOFF_MS
            while (isActive && !recoveryRequired) {
                signal.wake()
                try {
                    val properties = captureProperties(database, config)
                    dataSource.connection.use { connection ->
                        connection.autoCommit = true
                        val lock = CaptureLock(connection, database.type, config.identity)
                        lock.use {
                            val state = CaptureState(
                                config,
                                connection,
                                captureFingerprint(properties),
                                database.outboxTableName
                            )
                            val bootstrapping = bootstrapStateCreated && !offsetsSeen
                            bootstrapStateCreated = state.verify(bootstrapping) || bootstrapStateCreated
                            runEngine(connection, properties)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: CaptureRecoveryRequired) {
                    recoveryRequired = true
                    log.error(
                        "Capture requires explicit state recovery. SQL reconciliation continues. Reason: {}",
                        e.message
                    )
                } catch (e: CaptureAlreadyOwned) {
                    recoveryRequired = true
                    log.error("Capture identity already has an active owner. SQL reconciliation continues.")
                } catch (e: Exception) {
                    log.warn(
                        "Capture interrupted; SQL reconciliation continues. Reason: {}",
                        ErrorSanitizer.sanitize(e)
                    )
                } finally {
                    healthy = false
                    signal.wake()
                }
                delay(backoff)
                backoff = (backoff * BACKOFF_MULTIPLIER).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    private suspend fun runEngine(connection: Connection, properties: java.util.Properties) = coroutineScope {
        var failure: Throwable? = null
        val consumer = DebeziumEngine.ChangeConsumer<ChangeEvent<String, String>> { records, committer ->
            records.forEach { record ->
                if (isInsert(record.value())) signal.wake()
                committer.markProcessed(record)
            }
            committer.markBatchFinished()
        }
        val active = DebeziumEngine.create(Json::class.java)
            .using(properties)
            .using(
                DebeziumEngine.CompletionCallback { success, _, error ->
                    if (!success) failure = error ?: IllegalStateException("Capture stopped unsuccessfully")
                }
            )
            .using(object : DebeziumEngine.ConnectorCallback {
                override fun taskStarted() {
                    healthy = true
                    signal.wake()
                }
                override fun taskStopped() {
                    healthy = false
                }
            })
            .notifying(consumer).build()
        engine = active
        val offsets = java.nio.file.Path.of(config.stateDirectory).resolve("offsets.dat")
        val monitor = launch {
            while (isActive) {
                delay(CONNECTION_CHECK_INTERVAL_MS)
                if (!offsetsSeen && java.nio.file.Files.exists(offsets)) offsetsSeen = true
                if (!connection.isValid(CONNECTION_CHECK_TIMEOUT_SECONDS)) {
                    healthy = false
                    closeEngine(active)
                    break
                }
            }
        }
        try {
            active.run()
            failure?.let { throw it }
        } finally {
            healthy = false
            monitor.cancelAndJoin()
            closeEngine(active)
            engine = null
        }
    }

    suspend fun shutdown() {
        healthy = false
        withContext(Dispatchers.IO) { engine?.let(::closeEngine) }
        scope.cancel()
        scope.coroutineContext.job.children.forEach { it.join() }
    }

    private companion object {
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val BACKOFF_MULTIPLIER = 2L
        const val CONNECTION_CHECK_INTERVAL_MS = 1_000L
        const val CONNECTION_CHECK_TIMEOUT_SECONDS = 2
    }

    private fun closeEngine(active: DebeziumEngine<ChangeEvent<String, String>>) {
        try {
            active.close()
        } catch (_: IllegalStateException) {
            // Debezium reports this when its completion callback has already closed the engine.
        }
    }
}

/**
 * A tombstone carries no value and never wakes delivery. An unreadable record wakes delivery,
 * because SQL stays the truth and one extra query costs less than a missed insert.
 */
internal fun isInsert(value: String?): Boolean {
    if (value == null) return false
    return try {
        val envelope = kotlinx.serialization.json.Json.parseToJsonElement(value).jsonObject
        val payload = envelope["payload"]?.takeUnless { it is kotlinx.serialization.json.JsonNull }
        val body = payload?.jsonObject ?: envelope
        body["op"]?.jsonPrimitive?.content in setOf("c", "r")
    } catch (_: IllegalArgumentException) {
        true
    } catch (_: kotlinx.serialization.SerializationException) {
        true
    }
}
