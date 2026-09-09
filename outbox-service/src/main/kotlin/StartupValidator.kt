package org.nxtspec

import org.nxtspec.transform.TransformEngine

/**
 * Thrown when a configured expression cannot compile.
 */
class InvalidTransformException(message: String, cause: Throwable?) : RuntimeException(message, cause)

/**
 * Thrown when a destination names a template field or a row column that is not permitted. F-092.
 */
class InvalidDestinationException(message: String) : RuntimeException(message)

/**
 * Compiles every configured transform expression at startup. See F-057.
 *
 * Without this step an invalid JSONata expression is discovered on the first message, per
 * message, forever. The failure names the configuration path of the offending expression, so an
 * operator can find it without reading a stack trace.
 */
object StartupValidator {

    /**
     * @throws InvalidTransformException on the first expression that does not compile
     */
    fun validateTransforms(config: QueueBoxConfig, engine: TransformEngine = TransformEngine()) {
        config.routes.forEachIndexed { index, route ->
            compile(engine, route.transform, "routes[$index].transform.expression")
        }

        config.destinations.forEach { (name, destination) ->
            compile(engine, destination.transform, "destinations.$name.transform.expression")
        }

        config.sources.forEach { (name, source) ->
            compile(engine, source.transform, "sources.$name.transform.expression")
        }
    }

    private fun compile(engine: TransformEngine, transform: TransformConfig?, path: String) {
        val expression = transform?.expression ?: return

        engine.validateExpression(expression).onFailure { error ->
            throw InvalidTransformException(
                "The transform expression at '$path' does not compile. Reason: ${error.message}",
                error
            )
        }
    }

    /**
     * Validates every RabbitMQ `exchange`, Kafka `topic`, and NATS `subject` template, and every
     * `exchangeFrom`, `topicFrom`, and `subjectFrom` column name, on every configured
     * destination. F-092.
     *
     * Without this step a template that names an unknown field renders empty, and an operator
     * finds the mistake only when a row fails to publish. This check fails the start instead, and
     * names both the offending value and the destination, so an operator with many destinations
     * can find the one at fault.
     *
     * @throws InvalidDestinationException on the first template field or column name that is not
     *   permitted
     */
    fun validateAddressTemplates(config: QueueBoxConfig) {
        config.destinations.forEach { (name, destination) ->
            when (destination) {
                is DestinationConfig.RabbitMQ -> {
                    validateTemplate(destination.exchange, name, "exchange")
                    validateColumn(destination.exchangeFrom, name, "exchangeFrom")
                }
                is DestinationConfig.Kafka -> {
                    validateTemplate(destination.topic, name, "topic")
                    validateColumn(destination.topicFrom, name, "topicFrom")
                }
                is DestinationConfig.Nats -> {
                    validateTemplate(destination.subject, name, "subject")
                    validateColumn(destination.subjectFrom, name, "subjectFrom")
                }
                is DestinationConfig.Http -> Unit
            }
        }
    }

    private fun validateTemplate(template: String, destinationName: String, fieldName: String) {
        RoutingKeyRenderer.PLACEHOLDER_PATTERN.findAll(template).forEach { match ->
            val field = match.groupValues[1].trim()
            val permitted = field in RoutingKeyRenderer.PERMITTED_TEMPLATE_FIELDS ||
                RoutingKeyRenderer.PERMITTED_TEMPLATE_FIELD_PREFIXES.any { field.startsWith(it) }
            if (!permitted) {
                throw InvalidDestinationException(
                    "The '$fieldName' template of destination '$destinationName' names the field " +
                        "'$field', which is not permitted. Permitted fields are " +
                        "${RoutingKeyRenderer.PERMITTED_TEMPLATE_FIELDS.sorted().joinToString()}, " +
                        "and any field beginning with " +
                        "${RoutingKeyRenderer.PERMITTED_TEMPLATE_FIELD_PREFIXES.sorted().joinToString()}."
                )
            }
        }
    }

    private fun validateColumn(column: String?, destinationName: String, fieldName: String) {
        if (column == null) return

        if (column !in Destination.PERMITTED_ADDRESS_FROM_COLUMNS) {
            throw InvalidDestinationException(
                "The '$fieldName' of destination '$destinationName' names the column '$column', " +
                    "which is not permitted. Permitted columns are " +
                    "${Destination.PERMITTED_ADDRESS_FROM_COLUMNS.sorted().joinToString()}."
            )
        }
    }
}
