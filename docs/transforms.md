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
| `$attempt` | Current delivery attempt (1-based) |
| `$timestamp` | Current ISO timestamp |
| `$source` | Source name (inbox only) |

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
  onError: fail    # fail | skip | dead
```

- `fail` — Mark message as failed, retry later (default)
- `skip` — Skip this message, mark as sent
- `dead` — Move directly to dead-letter

## Routing Key Templates

For RabbitMQ destinations, you can dynamically construct routing keys from message data:

```yaml
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

**Precedence.** The route `routingKeyTemplate` wins. QueueBox renders it, and the RabbitMQ
publisher uses the result. A RabbitMQ destination also has its own `routingKeyTemplate`, which
supports `{{ topic }}` only. That destination template applies only when the matched route sets
no `routingKeyTemplate`.

**RabbitMQ throughput.** The publisher awaits one broker confirm per message. A measured run gave
1038 messages per second for 1000 messages on one destination. The test
`rabbitmq/src/test/kotlin/RabbitPublisherThroughputTest.kt` produced this figure on a developer
laptop, with the broker in a local container.

