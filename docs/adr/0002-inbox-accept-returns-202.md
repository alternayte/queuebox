# 2. The inbox accept response returns 202

## Status

Accepted.

## Context

`POST /inbox/<source>` stores a message in the inbox table. It returned 200 OK for a message that
it stored, and 200 OK again for a duplicate message.

The response is wrong for two reasons. A stored message is not a completed message. The relay moves
the row to the outbox later, and the poller delivers it later again. 202 Accepted is the code for
work that the server accepted but did not complete. The single code 200 also gave the caller no way
to tell a new message from a duplicate message without a read of the response body.

Finding F-078 of `hardening-doc.md` recorded the problem. Section 2A of the same document holds the
product decision of the maintainer. The decision is authoritative and closed.

## Decision

The route returns 202 Accepted for a message that it stores. The route returns 200 OK for a
duplicate message. The response body of each branch does not change.

QueueBox has no public consumer at this release, so the change needs no compatibility shim and no
configuration flag. The old behaviour is not available.

The complete set of codes that the route returns is now:

| Code | Meaning |
|------|---------|
| 202 | The message is new and stored. |
| 200 | The message is a duplicate of a stored message. |
| 400 | The body is not JSON, or the idempotency key path found no value. |
| 401 | The source requires authentication, and the request failed the check. |
| 413 | The body is larger than the configured cap. |
| 422 | The transform rejected the payload. |
| 429 | The request went over the per-source rate limit. |
| 500 | The storage layer failed. |

## Consequences

A caller that tests for the exact code 200 breaks. A caller that tests for any 2xx code does not.
`CHANGELOG.md` records the change under a `Breaking changes` heading in the first release.

A caller can now separate a new message from a duplicate message by the status code alone. That
makes a webhook sender able to count real deliveries without a body parse.

The test `the route enumerates every response code that it can return` in
`inbox-service/src/test/kotlin/org/nxtspec/InboxRoutesTest.kt` drives the route to each code above
and asserts the whole set. A new code, or a lost code, fails that test. The README response table
must equal that set.
