# Security policy

QueueBox handles database credentials, broker credentials, and inbound webhooks. A defect in that
area can expose a secret or accept a forged message. Report such a defect privately.

## Supported versions

| Version | Supported |
|---------|-----------|
| 0.1.x | Yes |
| Older than 0.1.0 | No. No release exists. |

QueueBox supports the latest minor release only. A fix ships in a new patch release of that minor
version.

## How to report a vulnerability

Use GitHub private vulnerability reporting. Open
<https://github.com/AlterNayte/queuebox/security/advisories/new> and complete the form. The report
stays private until a fix is published.

Do not open a public issue for a vulnerability. Do not describe the defect in a pull request.

Include this information in the report.

1. The affected version or commit.
2. The configuration that reproduces the defect.
3. The steps to reproduce the defect.
4. The impact that you observed.

## Response times

| Step | Time |
|------|------|
| Acknowledgement of the report | 3 working days |
| First assessment with a severity | 10 working days |
| Fix or a documented mitigation for a high severity defect | 30 days after the assessment |

The maintainer credits the reporter in the advisory, unless the reporter asks for anonymity.

## What QueueBox treats as a vulnerability

- A leak of a credential into a log line, a metric, an error body, or an HTTP response.
- A failure of the inbox authentication. This includes a bypass of the HMAC signature check, a
  timing side channel in a credential comparison, and acceptance of a replayed request.
- A missing or wrong check on an outbound URL that lets a configured route reach an unintended
  host.
- SQL injection through a table name, a column name, or a message field.
- A path that lets an unauthenticated caller read or change data through the admin endpoints.
- A denial of service that an unauthenticated caller can trigger, such as an unbounded request
  body or an unbounded memory allocation.
- A dependency with a known vulnerability that QueueBox reaches on a live code path.

## What QueueBox does not treat as a vulnerability

- A defect that needs write access to the configuration file. The configuration is trusted input.
- A missing hardening option that costs nothing to add. Open a normal feature request.
- A vulnerability report from a scanner with no reachable code path and no proof of concept.
- Plain HTTP or plain AMQP on a route that the operator configured without transport security.
  See `docs/operations/security.md`.
