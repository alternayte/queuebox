# A pull worker in TypeScript

This example is the README example of `@queuebox/inbox`, as a program that runs.

The continuous integration packs the library, installs the tarball, and type checks and runs
this project. That is the evidence for item 14 of the Definition of Done: the published package
installs and runs the README example unchanged.

## Run it

Start the PostgreSQL service of the root Compose setup and run QueueBox with a `pull` source
named `orders`, as `examples/pull/README.md` describes. Apply `schema.sql`. Then:

```bash
npm install
npm start
```

POST a message and watch the worker take it:

```bash
curl -X POST localhost:8080/inbox/orders -H 'content-type: application/json' \
  -d '{"id":"order-1","total":42}'
```

The `orders` table and the completion commit together. Stop the worker with Ctrl+C: it stops
claiming, lets the handler finish, and completes nothing that it abandoned.

Node 22 or later runs this file directly, because it strips the types. Bun and Deno run it too.
