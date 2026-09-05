package org.nxtspec.capture

import org.nxtspec.CaptureConfig
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.UUID

internal class CaptureAlreadyOwned : IllegalStateException()
internal class CaptureRecoveryRequired(reason: String) : IllegalStateException(reason)

internal class CaptureLock(private val connection: Connection, private val type: String, identity: String) :
    AutoCloseable {
    private val resource = "queuebox_capture_$identity"
    private val key = resource.hashCode().toLong()
    init {
        val sql = if (type == "postgresql") {
            "SELECT pg_try_advisory_lock(?)"
        } else {
            "DECLARE @r int; EXEC @r = sp_getapplock @Resource=?, @LockMode='Exclusive', " +
                "@LockOwner='Session', @LockTimeout=0; SELECT @r;"
        }
        val won = connection.prepareStatement(sql).use { statement ->
            if (type == "postgresql") statement.setLong(1, key) else statement.setString(1, resource)
            statement.executeQuery().use {
                it.next()
                if (type == "postgresql") it.getBoolean(1) else it.getInt(1) >= 0
            }
        }
        if (!won) throw CaptureAlreadyOwned()
    }
    override fun close() {
        val sql = if (type == "postgresql") {
            "SELECT pg_advisory_unlock(?)"
        } else {
            "EXEC sp_releaseapplock @Resource=?, @LockOwner='Session';"
        }
        connection.prepareStatement(sql).use {
            if (type == "postgresql") it.setLong(1, key) else it.setString(1, resource)
            it.execute()
        }
    }
}

/**
 * The database marker detects a missing volume; recovery must be an operator decision.
 * The fingerprint stops offsets of one capture configuration reaching a different one.
 */
internal class CaptureState(
    private val config: CaptureConfig,
    private val connection: Connection,
    private val fingerprint: String,
    private val outboxTable: String
) {
    /**
     * Returns true when this call created the registry row. The caller passes that result back as
     * [allowUnflushedBootstrap] on a retry, so a transient failure before the first offset flush
     * does not look like a lost volume.
     */
    fun verify(allowUnflushedBootstrap: Boolean = false): Boolean {
        val directory = Path.of(config.stateDirectory)
        val marker = directory.resolve("identity")
        val existing = readRegistry()
        if (existing != null) {
            verifyExisting(directory, marker, existing, allowUnflushedBootstrap)
            return false
        }
        return createRegistry(directory, marker)
    }

    private fun verifyExisting(directory: Path, marker: Path, existing: Registry, allowUnflushedBootstrap: Boolean) {
        if (existing.fingerprint != fingerprint) {
            throw CaptureRecoveryRequired(
                "The capture settings changed after the recorded state. Recover the state explicitly."
            )
        }
        if (stateFilesMissing(directory, marker, existing.stateId) && !allowUnflushedBootstrap) {
            throw CaptureRecoveryRequired("The persistent capture state is missing or belongs to another instance.")
        }
        // This process wrote the registry row and has not flushed an offset yet, so the
        // connector may not have created the slot. Demanding it here would turn a transient
        // first-start failure into a recovery request.
        preflight(slotMustExist = !allowUnflushedBootstrap)
    }

    private fun createRegistry(directory: Path, marker: Path): Boolean {
        Files.createDirectories(directory)
        if (Files.exists(marker) || Files.exists(directory.resolve("offsets.dat"))) {
            throw CaptureRecoveryRequired("Capture state files exist without a registry row.")
        }
        preflight(slotMustExist = false)
        val id = UUID.randomUUID().toString()
        Files.writeString(marker, id)
        connection.prepareStatement(
            "INSERT INTO queuebox_capture_state(identity_name, state_id, config_fingerprint) VALUES (?, ?, ?)"
        ).use {
            it.setString(1, config.identity)
            it.setString(2, id)
            it.setString(FINGERPRINT_PARAMETER, fingerprint)
            it.executeUpdate()
        }
        return true
    }

    private data class Registry(val stateId: String, val fingerprint: String)

    private fun readRegistry(): Registry? = connection.prepareStatement(
        "SELECT state_id, config_fingerprint FROM queuebox_capture_state WHERE identity_name = ?"
    ).use {
        it.setString(1, config.identity)
        it.executeQuery().use { rows -> if (rows.next()) Registry(rows.getString(1), rows.getString(2)) else null }
    }

    private fun stateFilesMissing(directory: Path, marker: Path, existing: String): Boolean =
        !Files.isRegularFile(marker) ||
            Files.readString(marker) != existing ||
            !Files.isRegularFile(directory.resolve("offsets.dat")) ||
            sqlServerHistoryMissing(directory)

    private fun sqlServerHistoryMissing(directory: Path): Boolean =
        config.mode == "sqlserver-cdc" && !Files.isRegularFile(directory.resolve("history.dat"))

    /**
     * Checks the database objects the connector needs. Debezium creates a missing PostgreSQL slot
     * without an error, which silently discards the recorded position, so the check runs first.
     */
    private fun preflight(slotMustExist: Boolean) {
        if (config.mode == "postgres-logical") {
            postgresPreflight(slotMustExist)
        } else {
            sqlServerPreflight()
        }
    }

    private fun postgresPreflight(slotMustExist: Boolean) {
        require(
            exists("SELECT 1 FROM pg_publication WHERE pubname = ?", config.publication)
        ) { "The publication ${config.publication} does not exist. Create it before you enable capture." }
        if (slotMustExist &&
            !exists("SELECT 1 FROM pg_replication_slots WHERE slot_name = ?", config.slot)
        ) {
            throw CaptureRecoveryRequired(
                "The replication slot ${config.slot} disappeared while capture state remains. " +
                    "Recover the state explicitly."
            )
        }
    }

    private fun sqlServerPreflight() {
        require(
            exists("SELECT 1 FROM sys.databases WHERE name = DB_NAME() AND is_cdc_enabled = 1", null)
        ) { "Change data capture is not enabled on this database." }
        require(
            exists("SELECT 1 FROM cdc.change_tables ct WHERE OBJECT_NAME(ct.source_object_id) = ?", outboxTable)
        ) { "Change data capture is not enabled on the outbox table." }
    }

    private companion object {
        /** The third bind variable of the registry insert. */
        const val FINGERPRINT_PARAMETER = 3
    }

    private fun exists(sql: String, parameter: String?): Boolean = connection.prepareStatement(sql).use {
        if (parameter != null) it.setString(1, parameter)
        it.executeQuery().use { rows -> rows.next() }
    }
}
