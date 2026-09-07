# A pull worker in C#

This example is the README example of `QueueBox.Inbox`, verbatim, as a program that runs.

The continuous integration packs the library, installs the package from a local feed, and
builds this project. That is the evidence for item 14 of the Definition of Done: the published
package installs and runs the README example unchanged.

## Run it

Start the PostgreSQL service of the root Compose setup and run QueueBox with a `pull` source
named `orders`, as `examples/pull/README.md` describes. Then:

```bash
dotnet run --project examples/pull/csharp
```

POST a message and watch the worker take it:

```bash
curl -X POST localhost:8080/inbox/orders -H 'content-type: application/json' \
  -d '{"id":"order-1","total":42}'
```

The `orders` table and the completion commit together. Stop the worker with Ctrl+C: it stops
claiming, lets the handler finish, and completes nothing that it abandoned.
