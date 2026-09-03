package org.nxtspec

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.time.Duration

/**
 * One RabbitMQ container for the whole JVM. A per-class container stops after the first
 * test class, and the next class then talks to a dead port. Ryuk removes the container
 * when the JVM exits.
 */
object RabbitTestContainer {
    private const val RABBITMQ_PORT = 5672

    val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("rabbitmq:3.12"))
        .withExposedPorts(RABBITMQ_PORT)
        .withTmpFs(mapOf("/var/lib/rabbitmq" to "rw,uid=999,gid=999"))
        .withEnv("RABBITMQ_ERLANG_COOKIE", "TESTCOOKIESTRINGLONGENOUGHFORERLANG")
        .waitingFor(
            Wait.forLogMessage(".*Server startup complete.*", 1)
                .withStartupTimeout(Duration.ofMinutes(2))
        )
        .also { it.start() }

    val amqpUrl: String
        get() = "amqp://guest:guest@${container.host}:${container.getMappedPort(RABBITMQ_PORT)}"
}
