package org.nxtspec

import org.nxtspec.repository.InboxRepositoryInterface
import org.nxtspec.repository.OutboxRepositoryInterface
import org.nxtspec.repository.RepositoryFactory
import javax.sql.DataSource

/**
 * Factory for creating SQL Server-specific repository implementations.
 */
class SqlServerRepositoryFactory(private val dataSource: DataSource) : RepositoryFactory {
    override fun createOutboxRepository(): OutboxRepositoryInterface = SqlServerOutboxRepository()

    override fun createInboxRepository(): InboxRepositoryInterface = SqlServerInboxRepository()
}
