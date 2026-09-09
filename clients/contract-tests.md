# The inbox client contract tests

Every QueueBox inbox client library passes this list. The list is language neutral on
purpose. A library states, for each item, the name of the test that proves it.

The source of truth for the behaviour is `client-libs-doc.md`, sections 2 and 5. The
source of truth for the SQL is `examples/pull/sql`. Neither is restated here.

## The list

| # | The library must | The test proves it by |
|---|------------------|-----------------------|
| 1 | Give the handler every field of the message | Claim one row with all fields set, then assert each field on the handler's argument. |
| 2 | Commit the handler's writes and the completion together | Let the handler insert a row. After the run, assert the row exists and the inbox state is `processed`. |
| 3 | Leave no application write behind when the handler throws | Let the handler insert a row and then throw. Assert the row is absent. |
| 4 | Roll the application's writes back when the completion affects zero rows | Steal the claim while the handler runs. Assert the handler's row is absent and no success is reported. |
| 5 | Never give one message to two workers | Run two workers on one backlog. Assert the union is the backlog and the intersection is empty. |
| 6 | Keep ownership across a handler longer than the lease | Set a short lease. Sleep in the handler past it. Assert the completion succeeds. |
| 7 | Cancel the handler and complete nothing when a renewal affects zero rows | Steal the claim, then assert the handler was cancelled and the row is not `processed`. |
| 8 | Return an abandoned message after the lease expires | Stop the worker while a handler runs. Wait past the lease. Assert a second claim returns the row. |
| 9 | Increment the attempt and apply the backoff on a retry | Throw from the handler. Assert `attempt` grew by one and `scheduled_at` moved forward. |
| 10 | Reach the dead letter at the attempt ceiling | Throw until the ceiling. Assert the state is `dead` and `last_error` is set. |
| 11 | Change nothing under a stale token | Reclaim the row, then run the completion, the retry and the dead letter with the old token. Assert each affects zero rows and the row is unchanged. |
| 12 | Claim nothing new after a stop, and abandon rather than complete | Signal the stop. Assert no further claim runs, and the in-flight row is not `processed`. |
| 13 | Work against a mapped table and column names | Create the schema under other names. Run the whole of item 2 against it. |
| 14 | Install and run the README example unchanged | Build a clean project against the published package and run the example. |
| 15 | Mask a secret in every log line and every error | Give a connection string that carries a password. Assert the password appears in no message. |
| 16 | Not poll in a storm when the claim is empty | Count the claims over a fixed window with an empty table. Assert the count matches the configured interval. |
| 17 | Never hold more than the batch in memory | Fill the table past the batch. Assert the in-flight count never passes the batch. |
| 18 | Hold at most one message per aggregate in flight | Fill the table with four messages of one aggregate and four of another. Run two workers. Assert that no two handler windows of one aggregate overlap, and that the two aggregates DO overlap. |
| 19 | Treat a claim lock failure as transient | Force sp_getapplock to return negative, for example by holding the lock past the timeout. Assert the library backs off and retries the whole call, and that it never marks the message failed or dead. |

Items 1 to 14 come from section 7 of the work order. Items 15 to 17 make the
cross-cutting requirements of section 8 testable, because a requirement without a test
is a wish. Items 18 and 19 come from finding F-087.

## Both dialects, and a real database

Every item runs against a real PostgreSQL and a real SQL Server, in containers, in
continuous integration. A library that passes against one dialect only is not done.
