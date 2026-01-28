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
import org.nxtspec.http.HttpPublisher
import org.nxtspec.repository.DatabaseProviderFactory
import org.nxtspec.repository.DatabaseType

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
    val repositoryFactory = DatabaseProviderFactory.create(dbType, dataSource)
    val outboxRepository = repositoryFactory.createOutboxRepository()
    val inboxRepository = repositoryFactory.createInboxRepository()

    // Convert config destinations to domain Destinations
    val destinations = config.destinations.mapValues { (name, destConfig) ->
        when (destConfig) {
            is DestinationConfig.Http -> Destination.Http(
                name = name,
                baseUrl = destConfig.baseUrl,
                path = destConfig.path,
                timeoutMs = destConfig.timeoutMs,
                headers = destConfig.headers
            )
            is DestinationConfig.RabbitMQ -> Destination.RabbitMQ(
                name = name,
                url = destConfig.url,
                exchange = destConfig.exchange,
                exchangeType = destConfig.exchangeType
            )
        }
    }

    // Publishers
    val httpPublisher = HttpPublisher()
    val publishers = listOf(httpPublisher)

    // Outbox service
    val router = MessageRouter(config.routes, destinations)
    val retryStrategy = RetryStrategy(config.outbox)
    val outboxPoller = OutboxPoller(config.outbox, outboxRepository, router, publishers, retryStrategy)

    // Inbox service
    val extractor = IdempotencyExtractor()
    val inboxHandler = InboxHandler(inboxRepository, extractor)

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
                )
            ) to connection
        }

    // Health manager
    val healthManager = HealthManager(dataSource)

    // Metrics
    val metricsCollector = MetricsCollector(prometheusRegistry)

    // Start poller
    outboxPoller.start()

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
            httpPublisher.close()
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
