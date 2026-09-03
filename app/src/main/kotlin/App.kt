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
import org.nxtspec.repository.ColumnMappingData
import org.nxtspec.repository.DatabaseProviderFactory
import org.nxtspec.repository.DatabaseType
import org.nxtspec.repository.InboxColumnMappingData
import org.nxtspec.repository.OutboxColumnMappingData
import org.nxtspec.transform.InboxTransformPipeline
import org.nxtspec.transform.TransformEngine
import org.nxtspec.transform.TransformPipeline

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
            claimedAt = config.database.columnMapping.outbox.claimedAt
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
        authResolver = authResolver
    )
    // F-003: RabbitMQ destinations are advertised, so the RabbitMQ publisher must be
    // registered. Without it the poller marks every RabbitMQ message as dead.
    val rabbitPublisher = RabbitPublisher(metricsCollector = metricsCollector)
    val publishers = listOf(httpPublisher, rabbitPublisher)

    // F-003: fail fast when a destination has no publisher.
    validatePublisherCoverage(destinations, publishers)

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

    // Register shutdown hook BEFORE starting the server
    Runtime.getRuntime().addShutdownHook(Thread {
        println("Shutting down QueueBox...")
        runBlocking {
            rabbitConsumers.forEach { (consumer, connection) ->
                consumer.stop()
                connection.close()
            }
            outboxPoller.shutdown()
            inboxRelay.shutdown()
            retentionService.stop()
            httpPublisher.close()
            rabbitPublisher.close()
            tokenManager.close()
            dataSource.close()
        }
        println("Shutdown complete")
    })

    // Start server
    embeddedServer(Netty, config.server.httpPort) {
        configureRouting()
        configureInboxRoutes(config.inbox, config.sources, inboxHandler)
        configureHealthRoutes(healthManager)
        configureMetricsRoutes(prometheusRegistry)
        configureAdminRoutes(transformEngine)
    }.start(wait = true)
}

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
