# ADR 0001: Reflection for the database providers

## Status

Accepted.

## Context

QueueBox supports PostgreSQL and SQL Server. Each database has its own module: `postgres` and
`sqlserver`. Each module holds a `RepositoryFactory` implementation, the tables, and the
migrations. Each module also carries its own JDBC driver and its own Exposed dialect.

The `core` module holds the repository interfaces and `DatabaseProviderFactory`. The factory must
return the implementation for the configured database type. A direct call needs a compile-time
dependency from `core` on both provider modules. That creates two problems.

- A dependency cycle. Both provider modules already depend on `core`.
- A fixed class path. Every deployment then carries both JDBC drivers, even when it uses one
  database.

The `app` module today depends on `postgres` and not on `sqlserver`. An adopter who wants SQL
Server adds the `sqlserver` module.

## Decision

`DatabaseProviderFactory` resolves the provider class by name with `Class.forName`. It looks up
`org.nxtspec.PostgresRepositoryFactory` or `org.nxtspec.SqlServerRepositoryFactory`, calls the
constructor that takes a `DataSource` and a `ColumnMappingData`, and casts the result to
`RepositoryFactory`.

The public signature of `create` stays as it is. An internal overload takes the class loader. That
overload is the seam that `DatabaseProviderFactoryTest` uses to prove the failure mode.

## Consequences

The dependency direction stays one way. Both provider modules depend on `core`, and `core` depends
on neither. A deployment carries only the driver of the database that it uses.

The cost is that the class path is checked at run time, not at compile time. Before finding F-080
was closed, an absent provider module raised a raw `java.lang.ClassNotFoundException` with the class
name and nothing else. The user got no statement of the cause and no repair step.

The failure mode now is `MissingDatabaseProviderException`. It carries the database type, the
Gradle module name, and the class name. Its message names the module that the user must add, for
example `postgres`. It keeps the `ClassNotFoundException` as its cause, so the stack trace still
shows the reflection site. `DatabaseProviderFactoryTest` proves this with a class loader that hides
every provider class.

A second cost remains. A change to the constructor signature of a provider factory breaks at run
time, not at compile time. The integration tests of each provider module cover that risk.
