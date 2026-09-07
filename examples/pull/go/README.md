# A pull worker in Go

This example is the README example of the Go client library, as a program that runs.

## Run it

Start the PostgreSQL service of the root Compose setup and run QueueBox with a `pull` source
named `orders`, as `examples/pull/README.md` describes. Apply `schema.sql`. Then:

```bash
go run .
```

POST a message and watch the worker take it:

```bash
curl -X POST localhost:8080/inbox/orders -H 'content-type: application/json' \
  -d '{"id":"order-1","total":42}'
```

The `orders` table and the completion commit together. Stop the worker with Ctrl+C: it stops
claiming, lets the handler finish, and completes nothing that it abandoned.

## The replace directive

`go.mod` here replaces the library with the copy in this repository, so the example builds
against the working tree. An application outside this repository needs no replace:

```bash
go get github.com/alternayte/queuebox/clients/go
```
