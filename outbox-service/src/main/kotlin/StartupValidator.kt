package org.nxtspec

import org.nxtspec.transform.TransformEngine

/**
 * Thrown when a configured expression cannot compile.
 */
class InvalidTransformException(message: String, cause: Throwable?) : RuntimeException(message, cause)

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
}
