package org.nxtspec

import java.lang.reflect.Proxy
import java.sql.Connection
import javax.sql.DataSource

/**
 * A data source that refuses every call. A test delegates to it and overrides the one method it
 * needs, so the test carries no unrelated stub code.
 */
val NoDataSource: DataSource = Proxy.newProxyInstance(
    DataSource::class.java.classLoader,
    arrayOf(DataSource::class.java)
) { _, method, _ -> throw UnsupportedOperationException(method.name) } as DataSource

/**
 * A connection that refuses every call, for the same reason.
 */
val NoConnection: Connection = Proxy.newProxyInstance(
    Connection::class.java.classLoader,
    arrayOf(Connection::class.java)
) { _, method, _ -> throw UnsupportedOperationException(method.name) } as Connection
