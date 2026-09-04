package org.nxtspec

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.ConnectionFactory
import com.zaxxer.hikari.HikariDataSource
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.nxtspec.transform.InboxTransformPipeline
import org.nxtspec.transform.InboxTransformResult
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals

/**
 * Covers the third adversarial review gate, defect 1.
 *
 * The consumer must never leave a rejected payload in state 'pending'. A relay that polls at the
 * same time must therefore never claim the row. The test runs a real claim loop against a real
 * PostgreSQL inbox, so it proves the order, not only the final state.
 */
@Tag("integration")
@Testcontainers
class RabbitConsumerRejectedClaimRaceTest {

    companion object {
        private const val RABBITMQ_PORT = 5672
        private const val TEST_QUEUE = "test-rejected-claim-race-queue"
        private const val REJECTED_COUNT = 20
    }

    @Container
    private val rabbitMQContainer = GenericContainer(DockerImageName.parse("rabbitmq:3.12"))
        .withExposedPorts(RABBITMQ_PORT)
        .withTmpFs(mapOf("/var/lib/rabbitmq" to "rw,uid=999,gid=999"))
        .withEnv("RABBITMQ_ERLANG_COOKIE", "TESTCOOKIESTRINGLONGENOUGHFORERLANG")
        .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1).withStartupTimeout(Duration.ofMinutes(2)))

    @Container
    private val postgresContainer = PostgreSQLContainer(DockerImageName.parse("postgres:16"))
        .withDatabaseName("queuebox_test")
        .withUsername("test")
        .withPassword("test")
        .withTmpFs(mapOf("/var/lib/postgresql/data" to "rw"))
        .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)))

    private val amqpUrl: String
        get() = "amqp://guest:guest@${rabbitMQContainer.host}:${rabbitMQContainer.getMappedPort(RABBITMQ_PORT)}"

    private lateinit var connection: RabbitConnection
    private lateinit var consumer: RabbitConsumer
    private lateinit var dataSource: HikariDataSource
    private lateinit var repository: InboxRepository

    private val claimScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @BeforeEach
    fun setup() {
        dataSource = DatabaseFactory.create(
            DatabaseConfig(
                url = postgresContainer.jdbcUrl,
                username = postgresContainer.username,
                password = Secret(postgresContainer.password),
                poolSize = 10
            )
        )
        DatabaseFactory.init(dataSource)
        PostgresMigrator().migrate(dataSource)
        repository = InboxRepository()
        connection = RabbitConnection(amqpUrl)
        declareTestQueue()
    }

    @AfterEach
    fun teardown() {
        claimScope.cancel()
        runBlocking {
            if (::consumer.isInitialized) {
                consumer.stop()
            }
            connection.close()
        }
        dataSource.close()
    }

    private fun declareTestQueue() {
        val factory = ConnectionFactory().apply { setUri(amqpUrl) }
        factory.newConnection().use { conn ->
            conn.createChannel().use { channel ->
                channel.queueDeclare(TEST_QUEUE, false, false, false, null)
                channel.queuePurge(TEST_QUEUE)
            }
        }
    }

    private fun publishMessage(payload: String) {
        val factory = ConnectionFactory().apply { setUri(amqpUrl) }
        factory.newConnection().use { conn ->
            conn.createChannel().use { channel ->
                channel.basicPublish("", TEST_QUEUE, AMQP.BasicProperties.Builder().build(), payload.toByteArray())
            }
        }
    }

    private fun rejectingPipeline(): InboxTransformPipeline = mockk<InboxTransformPipeline>().also { pipeline ->
        coEvery { pipeline.transform(any(), any(), any()) } returns
            InboxTransformResult.Rejected("Transform failed")
    }

    private val rejectingTransform = TransformConfig(
        expression = "$",
        onError = TransformErrorStrategy.Fail
    )

    @Test
    fun `a concurrent relay never claims a rejected payload`() = runBlocking {
        val claimed = CopyOnWriteArrayList<Pair<String, String>>()

        // The relay of the product polls in its own coroutine. This loop claims the same way.
        claimScope.launch {
            while (isActive) {
                val batch = repository.claimPending(10)
                batch.forEach { claimed.add(it.idempotencyKey to it.payload.toString()) }
                delay(5)
            }
        }

        val config = RabbitConsumerConfig(
            queueName = TEST_QUEUE,
            sourceName = "race-source",
            idempotencyKeyPath = "$.id"
        )
        consumer = RabbitConsumer(
            connection = connection,
            storeMessage = repository::store,
            extractor = IdempotencyExtractor(),
            config = config,
            transformPipeline = rejectingPipeline(),
            sourceTransform = rejectingTransform,
            storeDeadMessage = repository::storeDead
        )
        consumer.start()

        repeat(REJECTED_COUNT) { index ->
            publishMessage("""{"id": "k-$index", "secretField": "rejected-payload"}""")
        }

        delay(4000)
        consumer.stop()
        claimScope.cancel()

        assertEquals(
            REJECTED_COUNT.toLong(),
            repository.countByState("dead"),
            "Every rejected row must end in state 'dead'."
        )
        assertEquals(
            0,
            claimed.size,
            "A rejected payload must never become claimable. claimed=$claimed"
        )
    }

    /**
     * Fourth review gate, defect 1. A healthy row must not die because a later, unrelated
     * message shares its idempotency key.
     *
     * Event A is healthy and reaches the inbox in state 'pending'. Event B carries the same
     * value in the field that `idempotencyKeyPath` names, and the transform rejects it. The
     * store of the dead row answers Duplicate. The consumer must mark nothing, so event A stays
     * claimable and the relay still forwards it.
     */
    @Test
    fun `a healthy pending row survives a rejected message that shares its idempotency key`() = runBlocking {
        val config = RabbitConsumerConfig(
            queueName = TEST_QUEUE,
            sourceName = "race-source",
            idempotencyKeyPath = "$.id"
        )
        // Event A is healthy. It reaches the inbox through the normal path.
        val healthy = InboxMessage(
            source = "race-source",
            idempotencyKey = "shared-key",
            payload = kotlinx.serialization.json.JsonObject(
                mapOf("id" to kotlinx.serialization.json.JsonPrimitive("shared-key"))
            )
        )
        assertEquals(InboxResult.Stored, repository.store(healthy))

        // Event B shares the key and the transform rejects it.
        consumer = RabbitConsumer(
            connection = connection,
            storeMessage = repository::store,
            extractor = IdempotencyExtractor(),
            config = config,
            transformPipeline = rejectingPipeline(),
            sourceTransform = rejectingTransform,
            storeDeadMessage = repository::storeDead
        )
        consumer.start()

        publishMessage("""{"id": "shared-key", "secretField": "rejected-payload"}""")

        delay(3000)
        consumer.stop()

        assertEquals(
            0L,
            repository.countByState("dead"),
            "The rejected duplicate must add no dead row, and it must kill no healthy row."
        )
        assertEquals(1L, repository.countByState("pending"), "The healthy row must stay pending.")

        val claimed = repository.claimPending(10)
        assertEquals(1, claimed.size, "The relay must still forward the healthy row.")
        assertEquals("shared-key", claimed.single().idempotencyKey)
    }
}
