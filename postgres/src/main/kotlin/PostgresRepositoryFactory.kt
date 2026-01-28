package org.nxtspec

import org.nxtspec.repository.InboxRepositoryInterface
import org.nxtspec.repository.OutboxRepositoryInterface
import org.nxtspec.repository.RepositoryFactory
import javax.sql.DataSource

/**
 * Factory for creating PostgreSQL-specific repository implementations.
 */
class PostgresRepositoryFactory(private val dataSource: DataSource) : RepositoryFactory {
    override fun createOutboxRepository(): OutboxRepositoryInterface = OutboxRepository()

    override fun createInboxRepository(): InboxRepositoryInterface = InboxRepository()
}
