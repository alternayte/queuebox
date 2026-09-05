package org.nxtspec.app

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.runBlocking
import org.nxtspec.*
import org.nxtspec.auth.DestinationAuthResolver
import org.nxtspec.auth.InboxAuthValidator
import org.nxtspec.auth.OAuth2TokenManager
import org.nxtspec.http.HttpPublisher
import org.nxtspec.logging.logger
import org.nxtspec.repository.ColumnMappingData
import org.nxtspec.repository.DatabaseProviderFactory
import org.nxtspec.repository.DatabaseType
import org.nxtspec.repository.InboxColumnMappingData
import org.nxtspec.repository.OutboxColumnMappingData
import org.nxtspec.transform.InboxTransformPipeline
import org.nxtspec.transform.TransformEngine
import org.nxtspec.transform.TransformPipeline

private val log = logger("org.nxtspec.app.QueueBox")

fun main() {
    // Load configuration
    val config = ConfigLoader.load()

    // Create Prometheus registry for metrics
    val prometheusRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    // Every check that reads the configuration alone runs before any database work. A
    // configuration error must not cost the database startup timeout, and it must not migrate
    // the schema first.
    // F-057: an invalid transform expression must stop the start, not every message.
    StartupValidator.validateTransforms(config)

    // Convert config destinations to domain Destinations
    val destinations = config.destinations.mapValues { (name, destConfig) ->
        when (destConfig) {
            is DestinationConfig.Http -> Destination.Http(
                name = name,
                baseUrl = destConfig.baseUrl,
                path = destConfig.path,
                timeoutMs = destConfig.timeoutMs,
                headers = destConfig.headers,
                authConfig = destConfig.auth
            )
            is DestinationConfig.RabbitMQ -> Destination.RabbitMQ(
                name = name,
                url = destConfig.url,
                exchange = destConfig.exchange,
                exchangeType = destConfig.exchangeType,
                headers = destConfig.headers
            )
        }
    }

    // Extract destination-level transforms
    val destinationTransforms = config.destinations.mapValues { (_, destConfig) ->
        destConfig.transform
    }

    // Metrics
    val metricsCollector = MetricsCollector(prometheusRegistry)

    // Authentication
    val tokenManager = OAuth2TokenManager()
    val authResolver = DestinationAuthResolver(tokenManager)

    // Publishers
    val httpPublisher = HttpPublisher(
        metricsCollector = metricsCollector,
        authResolver = authResolver,
        // F-039: the operator value bounds the error body that a failed publish keeps.
        httpConfig = config.http
    )
    // F-003: RabbitMQ destinations are advertised, so the RabbitMQ publisher must be
    // registered. Without it the poller marks every RabbitMQ message as dead.
    val rabbitPublisher = RabbitPublisher(metricsCollector = metricsCollector)
    val publishers = listOf(httpPublisher, rabbitPublisher)

    // F-003: fail fast when a destination has no publisher.
    validatePublisherCoverage(destinations, publishers)

    // F-034: fail fast when the admin routes are enabled with no authentication.
    requireAdminAuth(config.admin)

    // Seventh review gate, defect 4. `HikariDataSource` opens the pool in its constructor, so a
    // database that is not up yet throws here, with the JDBC URL in the cause chain. The call sat
    // outside the try, so that chain reached stderr and the retry loop below never ran. Both
    // failures now take the same guarded path.
    val dataSource = try {
        DatabaseFactory.create(config.database, prometheusRegistry)
    } catch (e: Exception) {
        log.error("The database pool could not start. Reason: {}", ErrorSanitizer.sanitize(e))
        throw DatabaseUnavailableException(
            "The database pool could not start. Check 'database.url', the credentials, and the " +
                "network. The failure was: ${ErrorSanitizer.sanitize(e)}"
        )
    }

    try {
        // F-056: wait for the database rather than exiting at once. An orchestrator otherwise
        // shows a crash loop with no useful message while the database comes up.
        DatabaseStartup.awaitConnection(dataSource, config.database.startupTimeoutMs)

        DatabaseFactory.init(dataSource)
    } catch (e: Exception) {
        // The shutdown hook does not exist yet, so the pool closes here.
        dataSource.close()
        throw e
    }

    // Repositories via factory pattern
    val dbType = DatabaseType.valueOf(config.database.type.uppercase())
    val columnMappingData = columnMappingData(config.database)
    val repositoryFactory = DatabaseProviderFactory.create(dbType, dataSource, columnMappingData)

    // F-030: apply the bundled migrations before anything reads a table.
    if (config.database.migrate) {
        requireDefaultSchemaForMigrations(config.database)
        val applied = repositoryFactory.createMigrator().migrate(dataSource)
        log.info("Applied {} migration(s).", applied)
    }

    val outboxRepository = repositoryFactory.createOutboxRepository()
    val inboxRepository = repositoryFactory.createInboxRepository()
    val transactionRunner = repositoryFactory.createTransactionRunner()

    // Transform pipelines (shared engine for both outbox and inbox)
    val transformEngine = TransformEngine()
    // F-052: the pipeline counts a transform failure by strategy.
    val transformPipeline = TransformPipeline(transformEngine, metricsCollector)
    val inboxTransformPipeline = InboxTransformPipeline(transformEngine)

    // Retention service
    val retentionService = RetentionService(
        config.retention,
        outboxRepository,
        inboxRepository,
        metricsCollector
    )

    // Outbox service
    val router = MessageRouter(config.routes, destinations, destinationTransforms)
    val retryStrategy = RetryStrategy(config.outbox)
    val outboxPoller = OutboxPoller(
        config.outbox,
        outboxRepository,
        router,
        publishers,
        retryStrategy,
        metricsCollector,
        transformPipeline
    )

    // Inbox service
    val extractor = IdempotencyExtractor()
    val inboxHandler = InboxHandler(
        repository = inboxRepository,
        extractor = extractor,
        metricsCollector = metricsCollector,
        transformPipeline = inboxTransformPipeline
    )

    // F-002: the inbox relay moves a stored inbox message into the outbox table. The outbox
    // machinery then routes, transforms and delivers it.
    val inboxRelay = InboxRelay(
        config = config.inbox.relay,
        inboxRepository = inboxRepository,
        outboxRepository = outboxRepository,
        transactionRunner = transactionRunner,
        sourceTopicTemplates = config.sources.mapValues { (_, source) -> source.topic },
        metricsCollector = metricsCollector
    )

    // RabbitMQ consumers for inbox sources
    val rabbitConsumers = config.sources
        .filterValues { it is SourceConfig.RabbitMQ }
        .map { (sourceName, sourceConfig) ->
            val rabbitConfig = sourceConfig as SourceConfig.RabbitMQ
            val connection = createSourceConnection(sourceName, rabbitConfig.connectionUrl)
            RabbitConsumer(
                connection = connection,
                storeMessage = inboxRepository::store,
                extractor = extractor,
                config = rabbitConsumerConfig(sourceName, rabbitConfig),
                metricsCollector = metricsCollector,
                transformPipeline = inboxTransformPipeline,
                sourceTransform = rabbitConfig.transform,
                // A transform rejection on an AMQP source must not destroy the message. The
                // consumer stores the original payload in state 'dead' in one transaction, so
                // the relay never sees a claimable rejected row. See the third review gate,
                // defect 1.
                storeDeadMessage = inboxRepository::storeDead
            ) to connection
        }

    // Health manager
    // F-050: the readiness answer covers the workers and every RabbitMQ connection.
    val rabbitSourceNames = config.sources
        .filterValues { it is SourceConfig.RabbitMQ }
        .keys
        .toList()
    val healthContributors = buildList {
        add(SimpleHealthContributor("outbox-poller") { outboxPoller.isRunning() })
        addAll(retentionHealthContributors(config.retention.enabled) { retentionService.isRunning() })
        add(SimpleHealthContributor("inbox-relay") { inboxRelay.isRunning() })
        rabbitSourceNames.forEachIndexed { index, sourceName ->
            val consumer = rabbitConsumers[index].first
            add(SimpleHealthContributor("rabbitmq.$sourceName") { consumer.isChannelOpen })
        }
    }
    val healthManager = HealthManager(dataSource, healthContributors)

    // F-029: hold the server reference, so the shutdown can stop it first.
    val requestDrain = RequestDrain()
    val server = embeddedServer(
        factory = Netty,
        environment = applicationEnvironment { },
        configure = {
            connector { port = config.server.httpPort }
        }
    ) {
        configureJson()
        configureRequestDrain(requestDrain)
        // F-023: the cap applies to every route, not only to the inbox route.
        configureBodySizeLimit(config.inbox.maxBodyBytes)
        configureRouting()
        configureInboxRoutes(config.inbox, config.sources, inboxHandler)
        // F-051: the operational endpoints leave the data port when a management port is set.
        configureDataPortOperationalRoutes(
            managementPort = config.server.managementPort,
            prometheusRegistry = prometheusRegistry,
            healthManager = healthManager,
            adminConfig = config.admin,
            transformEngine = transformEngine
        )
    }

    // F-051: the second server carries the operational endpoints alone.
    val managementServer = config.server.managementPort?.let { port ->
        embeddedServer(
            factory = Netty,
            environment = applicationEnvironment { },
            configure = {
                connector { this.port = port }
            }
        ) {
            // The management server is its own Ktor application, so it needs its own plugins.
            configureJson()
            configureOperationalRoutes(
                prometheusRegistry = prometheusRegistry,
                healthManager = healthManager,
                adminConfig = config.admin,
                transformEngine = transformEngine
            )
        }
    }

    val shutdownSequence = ShutdownSequence(
        stopServer = {
            // Refuse new requests, then wait for the in-flight ones. The Ktor stop cancels a
            // running handler, so the wait must finish first.
            requestDrain.startDraining()
            val drained = requestDrain.await(SHUTDOWN_GRACE_PERIOD_MS)
            if (!drained) {
                log.warn(
                    "The shutdown drain timed out. {} request(s) are still in flight.",
                    requestDrain.count()
                )
            }
            // Netty refuses a timeout below the grace period, so the timeout is at least the
            // grace period. Without the guard a small outbox.shutdownTimeoutMs makes the stop
            // throw, and the remaining stops never run.
            val stopTimeoutMs = maxOf(config.outbox.shutdownTimeoutMs, SHUTDOWN_GRACE_PERIOD_MS)

            // Each stop has its own try, so a failure of one still stops the other. Both must
            // stop before the resources close.
            stopQuietly("the data server") {
                server.stop(
                    gracePeriodMillis = SHUTDOWN_GRACE_PERIOD_MS,
                    timeoutMillis = stopTimeoutMs
                )
            }
            stopQuietly("the management server") {
                managementServer?.stop(
                    gracePeriodMillis = SHUTDOWN_GRACE_PERIOD_MS,
                    timeoutMillis = stopTimeoutMs
                )
            }
        },
        stopBackgroundServices = {
            rabbitConsumers.forEach { (consumer, connection) ->
                stopQuietly("a RabbitMQ consumer") { consumer.stop() }
                stopQuietly("a RabbitMQ connection") { connection.close() }
            }
            stopQuietly("the outbox poller") { outboxPoller.shutdown() }
            stopQuietly("the inbox relay") { inboxRelay.shutdown() }
            stopQuietly("the retention service") { retentionService.stop() }
        },
        closeResources = {
            stopQuietly("the HTTP publisher") { httpPublisher.close() }
            stopQuietly("the RabbitMQ publisher") { rabbitPublisher.close() }
            stopQuietly("the token manager") { tokenManager.close() }
            stopQuietly("the data source") { dataSource.close() }
        }
    )

    // Register the shutdown hook BEFORE anything starts a thread.
    //
    // The RabbitMQ client connection thread is not a daemon thread. A failure after a consumer
    // starts, but before the hook exists, would leave a process with no server, an open pool and
    // a live thread, which never exits. An orchestrator cannot tell that apart from a healthy
    // start.
    Runtime.getRuntime().addShutdownHook(
        Thread {
            runBlocking { shutdownSequence.run() }
        }
    )

    try {
        outboxPoller.start()
        retentionService.start()
        inboxRelay.start()
        runBlocking {
            rabbitConsumers.forEach { (consumer, _) -> consumer.start() }
        }

        managementServer?.start(wait = false)
        server.start(wait = true)
    } catch (e: Exception) {
        log.error(
            "The start failed. QueueBox releases every resource that it holds. Reason: {}",
            ErrorSanitizer.sanitize(e)
        )
        runBlocking { shutdownSequence.run() }
        throw e
    }
}

/**
 * Builds the AMQP connection of one inbox source.
 *
 * `RabbitConnection` parses the URI in its constructor, and the message of a
 * `URISyntaxException` embeds the whole URI, which carries the broker password. An uncaught
 * failure leaves `main`, and the JVM prints it to stderr, which is the container log. The call
 * therefore runs inside a try, and the replacement message names the source alone.
 *
 * The start fails. QueueBox does not continue without the source. A source that fails silently
 * consumes nothing, so the queue grows and no operator is told. `RabbitPublisher` treats the
 * same failure the same way, as a sanitised failure rather than a raw URI.
 *
 * The cause is not attached, because the cause message carries the URI as well.
 */
internal fun createSourceConnection(sourceName: String, connectionUrl: String): RabbitConnection = try {
    RabbitConnection(connectionUrl)
} catch (e: Exception) {
    log.error(
        "The connection URL of the RabbitMQ source '{}' is not a valid AMQP URI. " +
            "QueueBox does not print the URL, because it carries the broker password.",
        sourceName
    )
    throw IllegalStateException(
        "The connection URL of the RabbitMQ source '" + sourceName +
            "' is not a valid AMQP URI. Correct sources." + sourceName +
            ".connectionUrl. The URL is not printed, because it carries the broker password."
    )
}

/**
 * Maps the configured column names onto the repository data class.
 *
 * Fourth review gate, defect 2: every name of the configuration must appear here. A dropped
 * name passes the configuration validator, and then every statement of that table fails.
 */
internal fun columnMappingData(database: DatabaseConfig): ColumnMappingData = ColumnMappingData(
    outbox = OutboxColumnMappingData(
        id = database.columnMapping.outbox.id,
        topic = database.columnMapping.outbox.topic,
        key = database.columnMapping.outbox.key,
        payload = database.columnMapping.outbox.payload,
        headers = database.columnMapping.outbox.headers,
        state = database.columnMapping.outbox.state,
        attempt = database.columnMapping.outbox.attempt,
        maxAttempts = database.columnMapping.outbox.maxAttempts,
        scheduledAt = database.columnMapping.outbox.scheduledAt,
        createdAt = database.columnMapping.outbox.createdAt,
        updatedAt = database.columnMapping.outbox.updatedAt,
        claimedAt = database.columnMapping.outbox.claimedAt,
        lastError = database.columnMapping.outbox.lastError
    ),
    inbox = InboxColumnMappingData(
        id = database.columnMapping.inbox.id,
        source = database.columnMapping.inbox.source,
        idempotencyKey = database.columnMapping.inbox.idempotencyKey,
        aggregateId = database.columnMapping.inbox.aggregateId,
        eventType = database.columnMapping.inbox.eventType,
        payload = database.columnMapping.inbox.payload,
        state = database.columnMapping.inbox.state,
        createdAt = database.columnMapping.inbox.createdAt,
        processedAt = database.columnMapping.inbox.processedAt,
        claimedAt = database.columnMapping.inbox.claimedAt,
        correlationId = database.columnMapping.inbox.correlationId
    ),
    outboxTableName = database.outboxTableName,
    inboxTableName = database.inboxTableName
)

/**
 * Maps one RabbitMQ source of the configuration onto the consumer configuration.
 *
 * Every documented field of the source must reach the consumer. `aggregateIdPath` is
 * documented for ordered processing, so it belongs here.
 */
internal fun rabbitConsumerConfig(sourceName: String, source: SourceConfig.RabbitMQ): RabbitConsumerConfig =
    RabbitConsumerConfig(
        queueName = source.queueName,
        sourceName = sourceName,
        prefetchCount = source.prefetchCount,
        idempotencyKeyPath = source.idempotencyKeyPath,
        aggregateIdPath = source.aggregateIdPath,
        eventTypePath = source.eventTypePath
    )

/**
 * Registers the operational endpoints: the metrics endpoint, the health endpoints and the admin
 * endpoint. See F-051.
 */
fun Application.configureOperationalRoutes(
    prometheusRegistry: PrometheusMeterRegistry,
    healthManager: HealthManager,
    adminConfig: AdminConfig,
    transformEngine: TransformEngine
) {
    configureHealthRoutes(healthManager)
    configureMetricsRoutes(prometheusRegistry)
    configureAdminRoutes(adminConfig, InboxAuthValidator(), transformEngine)
}

/**
 * Registers the operational endpoints on the data port only while no management port exists.
 *
 * F-051: a set management port moves the metrics endpoint, the health endpoints and the admin
 * endpoint off the data port.
 */
fun Application.configureDataPortOperationalRoutes(
    managementPort: Int?,
    prometheusRegistry: PrometheusMeterRegistry,
    healthManager: HealthManager,
    adminConfig: AdminConfig,
    transformEngine: TransformEngine
) {
    if (managementPort != null) return
    configureOperationalRoutes(prometheusRegistry, healthManager, adminConfig, transformEngine)
}

/** Time that an in-flight request has to finish before the server stops. See F-029. */
private const val SHUTDOWN_GRACE_PERIOD_MS = 5000L

/**
 * Runs one shutdown step and reports a failure without stopping the remaining steps.
 *
 * A half-closed process holds a listening socket, a database connection, and a broker
 * connection. Every step therefore runs, whatever the previous step did.
 */
private suspend fun stopQuietly(what: String, action: suspend () -> Unit) {
    try {
        action()
    } catch (e: Exception) {
        log.warn("Stopping {} failed. The shutdown continues. Reason: {}", what, ErrorSanitizer.sanitize(e))
    }
}

/**
 * Installs the JSON content negotiation.
 *
 * Every server needs it, because the health routes and the admin routes answer with a
 * serializable object. The management server is a separate Ktor application, so it installs the
 * plugin itself. See F-051.
 */
fun Application.configureJson() {
    install(ContentNegotiation) {
        json()
    }
}

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respondText("QueueBox is running!")
        }
    }
}
