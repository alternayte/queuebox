# Entity Framework Core package

## What it does
`InboxDbContextFactory` moves from `QueueBox.Inbox.DependencyInjection` to a new NuGet package, `QueueBox.Inbox.EntityFrameworkCore`. The dependency injection package then depends on `QueueBox.Inbox` and `Microsoft.Extensions.*` alone. A consumer that wants only `AddQueueBoxInbox` takes no Entity Framework Core. Fixes #90. The release is C# 0.5.0.

## Decisions
- The first shape of #90, a package of its own — a lower floor still forces Entity Framework Core on every consumer.
- The namespace of the type becomes `QueueBox.Inbox.EntityFrameworkCore` — the namespace follows the package, and the move already needs a change in the consumer.
- The type keeps its name, its method and its behaviour — only the package and the namespace change.
- The new package depends on `QueueBox.Inbox` with an exact version, and on `Microsoft.EntityFrameworkCore.Relational` 8.0.11. It does not depend on the dependency injection package — the helper needs no container.
- The three packages ship from the one `csharp-v*` tag at one version. The workflow pushes the new package last and checks its pin — the lockstep rule of the two packages.
- 0.5.0, with the move under Breaking — below 1.0.0 a breaking change raises MINOR. The migration step is: add the package and change the `using`.
- The existing tests of the factory stay in their project and reference the new project — they prove the same behaviour.

## Out
- A lower Entity Framework Core floor.
- A type forward from the old package.
- A change to the TypeScript and Go clients.

## How I know it works
- The nuspec of `QueueBox.Inbox.DependencyInjection` 0.5.0 lists no `Microsoft.EntityFrameworkCore` dependency.
- The nuspec of `QueueBox.Inbox.EntityFrameworkCore` 0.5.0 pins `QueueBox.Inbox` to `[0.5.0]`.
- A project that pins Entity Framework Core 8.0.0 restores with `QueueBox.Inbox.DependencyInjection` and no NU1605 error.
- The C# unit, contract and dependency injection tests pass, on PostgreSQL and SQL Server.
