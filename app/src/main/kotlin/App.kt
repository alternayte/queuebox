package org.nxtspec.app

import com.zaxxer.hikari.HikariDataSource
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
import org.nxtspec.auth.OAuth2TokenManager
import org.nxtspec.http.HttpPublisher
import org.nxtspec.logging.logger
import org.nxtspec.repository.ColumnMappingData
import org.nxtspec.repository.DatabaseProviderFactory
import org.nxtspec.repository.DatabaseType
import org.nxtspec.repository.InboxColumnMappingData
import org.nxtspec.repository.OutboxColumnMappingData
import org.nxtspec.auth.InboxAuthValidator
import org.nxtspec.transform.InboxTransformPipeline
import org.nxtspec.transform.TransformEngine
import org.nxtspec.transform.TransformPipeline

private val log = logger("org.nxtspec.app.QueueBox")

fun main() {
    // Load configuration
    val config = ConfigLoader.load()

    // Create Prometheus registry for metrics
    val prometheusRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    // Database setup with metrics integration
    val dataSource = DatabaseFactory.create(config.database, prometheusRegistry)
    DatabaseFactory.init(dataSource)

    // Repositories via factory pattern
    val dbType = DatabaseType.valueOf(config.database.type.uppercase())
    val columnMappingData = ColumnMappingData(
        outbox = OutboxColumnMappingData(
            id = config.database.columnMapping.outbox.id,
            topic = config.database.columnMapping.outbox.topic,
            key = config.database.columnMapping.outbox.key,
            payload = config.database.columnMapping.outbox.payload,
            headers = config.database.columnMapping.outbox.headers,
            state = config.database.columnMapping.outbox.state,
            attempt = config.database.columnMapping.outbox.attempt,
            maxAttempts = config.database.columnMapping.outbox.maxAttempts,
            scheduledAt = config.database.columnMapping.outbox.scheduledAt,
            createdAt = config.database.columnMapping.outbox.createdAt,
            updatedAt = config.database.columnMapping.outbox.updatedAt,
            claimedAt = config.database.columnMapping.outbox.claimedAt,
            lastError = config.database.columnMapping.outbox.lastError
        ),
        inbox = InboxColumnMappingData(
            id = config.database.columnMapping.inbox.id,
            source = config.database.columnMapping.inbox.source,
            idempotencyKey = config.database.columnMapping.inbox.idempotencyKey,
            aggregateId = config.database.columnMapping.inbox.aggregateId,
            eventType = config.database.columnMapping.inbox.eventType,
            payload = config.database.columnMapping.inbox.payload,
            state = config.database.columnMapping.inbox.state,
            createdAt = config.database.columnMapping.inbox.createdAt,
            processedAt = config.database.columnMapping.inbox.processedAt,
            claimedAt = config.database.columnMapping.inbox.claimedAt
        ),
        outboxTableName = config.database.outboxTableName,
        inboxTableName = config.database.inboxTableName
    )
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

    // Transform pipelines (shared engine for both outbox and inbox)
    val transformEngine = TransformEngine()
    val transformPipeline = TransformPipeline(transformEngine)
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
            val connection = RabbitConnection(rabbitConfig.connectionUrl)
            RabbitConsumer(
                connection = connection,
                storeMessage = inboxRepository::store,
                extractor = extractor,
                config = RabbitConsumerConfig(
                    queueName = rabbitConfig.queueName,
                    sourceName = sourceName,
                    prefetchCount = rabbitConfig.prefetchCount,
                    idempotencyKeyPath = rabbitConfig.idempotencyKeyPath
                ),
                metricsCollector = metricsCollector,
                transformPipeline = inboxTransformPipeline,
                sourceTransform = rabbitConfig.transform
            ) to connection
        }

    // Health manager
    val healthManager = HealthManager(dataSource)

    // Start poller
    outboxPoller.start()

    // Start retention cleanup
    retentionService.start()

    // Start the inbox relay
    inboxRelay.start()

    // Start RabbitMQ consumers
    runBlocking {
        rabbitConsumers.forEach { (consumer, _) -> consumer.start() }
    }

    // F-029: hold the server reference, so the shutdown can stop it first.
    val requestDrain = RequestDrain()
    val server = embeddedServer(
        factory = Netty,
        environment = applicationEnvironment { },
        configure = {
            connector { port = config.server.httpPort }
        }
    ) {
        configureRequestDrain(requestDrain)
        // F-023: the cap applies to every route, not only to the inbox route.
        configureBodySizeLimit(config.inbox.maxBodyBytes)
        configureRouting()
        configureInboxRoutes(config.inbox, config.sources, inboxHandler)
        configureHealthRoutes(healthManager)
        configureMetricsRoutes(prometheusRegistry)
        configureAdminRoutes(config.admin, InboxAuthValidator(), transformEngine)
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
            server.stop(
                gracePeriodMillis = SHUTDOWN_GRACE_PERIOD_MS,
                timeoutMillis = config.outbox.shutdownTimeoutMs
            )
        },
        stopBackgroundServices = {
            rabbitConsumers.forEach { (consumer, connection) ->
                consumer.stop()
                connection.close()
            }
            outboxPoller.shutdown()
            inboxRelay.shutdown()
            retentionService.stop()
        },
        closeResources = {
            httpPublisher.close()
            rabbitPublisher.close()
            tokenManager.close()
            dataSource.close()
        }
    )

    // Register the shutdown hook BEFORE the server starts.
    Runtime.getRuntime().addShutdownHook(
        Thread {
            runBlocking { shutdownSequence.run() }
        }
    )

    server.start(wait = true)
}

/** Time that an in-flight request has to finish before the server stops. See F-029. */
private const val SHUTDOWN_GRACE_PERIOD_MS = 5000L

fun Application.configureRouting() {
    install(ContentNegotiation) {
        json()
    }
    routing {
        get("/") {
            call.respondText("QueueBox is running!")
        }
    }
}
