# Transforms

This document holds the transform reference. `README.md` links here.

## Transforms

QueueBox uses [JSONata](https://jsonata.org/) for payload transformations. Transforms can be applied at three levels:

1. **Source level** — transform incoming messages on ingestion
2. **Route level** — transform before routing
3. **Destination level** — transform before delivery

### Context Variables

Transforms have access to context variables:

| Variable | Description |
|----------|-------------|
| `$messageId` | UUID of the message |
| `$topic` | Message topic |
| `$attempt` | The number of failed deliveries so far. It is `0` on the first delivery. The retry raises it. |
| `$timestamp` | Current ISO timestamp |
| `$source` | Source name (inbox only) |

The count matches the `attempt` column of the outbox row, and it matches the `X-Attempt`
header that the HTTP publisher sends. [integration.md](integration.md) documents the column.
The test `outbox-service/src/test/kotlin/org/nxtspec/OutboxPollerTest.kt` pins the value `0` on
the first delivery.

### Examples

**Reshape payload:**
```jsonata
{
  "orderId": id,
  "customer": customer.name,
  "total": items.(price * quantity) ~> $sum(),
  "processedAt": $timestamp
}
```

**Filter fields:**
```jsonata
$ ~> |$|{}, ['password', 'secret', 'token']|
```

**Conditional logic:**
```jsonata
status = 'paid' ? { "action": "fulfill", "orderId": id } : { "action": "remind", "orderId": id }
```

### Error Handling

Configure what happens when a transform fails:

```yaml
transform:
  expression: "..."
  onError: Fail    # Fail | Skip | Dead
```

The loader matches the value against the enum name, so the value must carry the exact case above.

- `Fail` — Mark the message as failed and retry it later. This is the default.
- `Skip` — Keep the original payload. The transform result is discarded, and the untransformed
  payload continues to the destination. The message is not marked as sent, and it is not skipped.
- `Dead` — Move the message to the dead-letter state at once.

**A source transform on an AMQP source.** `Fail` and `Dead` both reject the message. QueueBox
stores the inbox row with the original payload, marks the row `dead`, and then acknowledges the
delivery. The message is never destroyed. The same rejection on an HTTP source answers 422 and
stores no row, because the caller still holds the message. See
[message-flow.md](message-flow.md).

## Routing Key Templates

For RabbitMQ destinations, you can dynamically construct routing keys from message data:

```yaml
destinations:
  events-exchange:
    type: rabbitmq
    url: amqp://rabbitmq:5672
    exchange: events

routes:
  - topicPattern: "order.*"
    destination: events-exchange
    routingKeyTemplate: "{{ payload.region }}.{{ payload.priority }}.{{ topic }}"
    routingKeyMissingFieldDefault: "default"
```

**Template variables:**
- `{{ topic }}` — The message topic.
- `{{ payload.fieldName }}` — A field from the message payload.
- `{{ data.fieldName }}` — An alias for `payload.fieldName`.
- Nested fields: `{{ payload.customer.region }}` or `{{ data.customer.region }}`.

Any other placeholder, including a bare field name such as `{{ region }}`, renders as the
`routingKeyMissingFieldDefault` value. If a field is missing, the same default applies. The
default is an empty string if `routingKeyMissingFieldDefault` is not configured.

The test
`outbox-service/src/test/kotlin/org/nxtspec/RoutingKeyTemplateContractTest.kt` is the source of
truth for the supported placeholder forms.

**Ordering under concurrency.** The poller claims a batch in order, oldest first, and then
publishes up to `outbox.concurrency` messages at the same time. Two messages of one batch can
therefore arrive at the destination out of order. Set `outbox.concurrency: 1` when a destination
needs strict order.

A RabbitMQ destination is the exception. It holds one confirmed channel, and one publish at a
time uses it, so `outbox.concurrency` raises throughput only across different destinations. An
HTTP destination has no such limit.

**Precedence.** `routingKeyTemplate` belongs to a route. QueueBox renders it, and the RabbitMQ
publisher uses the result. A RabbitMQ destination has no `routingKeyTemplate` field, so you cannot
configure a template on a destination. When the matched route sets no `routingKeyTemplate`, the
publisher falls back to the built-in destination template `{{ topic }}`, and the routing key is
therefore the message topic.

**RabbitMQ throughput.** The publisher awaits one broker confirm per message. A measured run gave
1038 messages per second for 1000 messages on one destination. The test
`rabbitmq/src/test/kotlin/RabbitPublisherThroughputTest.kt` produced this figure on a developer
laptop, with the broker in a local container.

