package org.nxtspec.app

import org.nxtspec.ConfigLoader
import org.nxtspec.logging.logger
import org.nxtspec.repository.DatabaseProviderFactory
import org.nxtspec.repository.DatabaseType

private val log = logger("org.nxtspec.app.MigrateCommand")

/** The command succeeded. */
internal const val EXIT_OK = 0

/** The command ran and failed. The log names the reason. */
internal const val EXIT_FAILED = 1

/** The arguments name no command. */
internal const val EXIT_USAGE = 2

private val BASELINE_ARGS = listOf("migrate", "--baseline")

internal const val USAGE = """Usage:
  queuebox                                start the service
  queuebox migrate                        apply the bundled migrations and exit
  queuebox migrate --baseline <version>   record <version> as applied by hand, then apply the later files and exit"""

/**
 * Runs the command that the arguments name and returns the process exit code. `main` calls this
 * for any argument list that is not empty.
 *
 * A job runner branches on the exit code, so every failure returns a non-zero code rather than
 * leaving a throwable to the JVM.
 *
 * @param env Supplier of the environment. A test replaces it. See [ConfigLoader.load].
 */
internal fun runCommand(args: List<String>, env: () -> Map<String, String> = { System.getenv() }): Int = when {
    args == listOf("migrate") -> runMigrate(baseline = null, env = env)
    args.size == BASELINE_ARGS.size + 1 && args.take(BASELINE_ARGS.size) == BASELINE_ARGS ->
        runMigrate(baseline = args.last(), env = env)
    else -> {
        System.err.println("Unknown arguments: ${args.joinToString(" ")}")
        System.err.println(USAGE)
        EXIT_USAGE
    }
}

/**
 * Applies the bundled migrations with the configuration of the service, and exits. Issue #66.
 *
 * A privileged operator runs it once per upgrade, so the service can run with
 * `database.migrate: false` and no DDL rights. The command ignores `database.migrate`, because
 * the command is the explicit request. It takes the same guard as the startup migration, so it
 * refuses a renamed table or column and a hand-applied database with no history.
 *
 * @param baseline the version to record as applied before the migration, or null for none
 */
internal fun runMigrate(baseline: String?, env: () -> Map<String, String>): Int = try {
    val config = startupStep("read its configuration") { ConfigLoader.load(env = env) }
    openDatabase(config.database, registry = null).use { dataSource ->
        val factory = startupStep("load the database provider") {
            val type = DatabaseType.valueOf(config.database.type.uppercase())
            DatabaseProviderFactory.create(type, dataSource, columnMappingData(config.database))
        }
        applyMigrations(factory.createMigrator(), dataSource, config.database, baseline)
    }
    EXIT_OK
} catch (e: StartupFailedException) {
    // The message is sanitised already, and it carries no cause.
    log.error("The migration failed. {}", e.message)
    EXIT_FAILED
}
