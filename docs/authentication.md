# Authentication and security

This document holds the authentication reference for a destination and for a source, and the
security notes. `README.md` links here.

## Security

Read [docs/operations/security.md](operations/security.md) before you put QueueBox on a
network that you do not control. It covers the transport, the secrets, the admin endpoint, and
the request limits.

Three rules matter most.

1. **Terminate TLS in front of QueueBox.** QueueBox listens on plain HTTP and does not terminate
   TLS. The document holds a working ingress example.
2. **Point a credential at a file.** Every credential field accepts a `file:` reference, so an
   operator can mount a Kubernetes secret. QueueBox reads the file once, at startup.
3. **A credential never prints.** Every credential field carries the `Secret` type, whose
   `toString` returns a mask.

## Authentication

QueueBox supports authentication for both incoming webhooks (inbox) and outgoing HTTP requests (destinations).

### Inbox Authentication

Protect your inbox endpoints from unauthorized requests.

**Bearer Token:**
```yaml
sources:
  secure-webhook:
    type: http
    path: /secure
    idempotencyKeyPath: $.id
    eventTypePath: $.type
    auth:
      type: bearer
      token: ${WEBHOOK_TOKEN}
```

**API Key:**
```yaml
auth:
  type: api-key
  headerName: X-API-Key           # Default header name
  key: ${API_KEY}
```

**HMAC Signature** (for Stripe, GitHub, etc.):
```yaml
auth:
  type: hmac
  secret: ${WEBHOOK_SECRET}
  headerName: X-Signature         # Header containing the signature
  algorithm: HmacSHA256           # HmacSHA256, HmacSHA1, etc.
  signaturePrefix: "sha256="      # Prefix before the signature
  timestampHeader: X-Timestamp    # Optional: for replay protection
  timestampTolerance: 300000      # Max age in ms (default: 5 min)
```

### Destination Authentication

Authenticate outgoing HTTP requests to protected APIs.

**OAuth2 Client Credentials:**
```yaml
destinations:
  protected-api:
    type: http
    baseUrl: https://api.example.com
    auth:
      type: oauth2
      clientId: ${CLIENT_ID}
      clientSecret: ${CLIENT_SECRET}
      tokenUrl: https://auth.example.com/oauth/token
      scope: api:write            # Optional scope
      extraParams:                # Optional additional params
        audience: https://api.example.com
```

**HTTP Basic:**
```yaml
auth:
  type: basic
  username: ${API_USER}
  password: ${API_PASSWORD}
```

**Custom Header:**
```yaml
auth:
  type: header
  headerName: Authorization       # Or any custom header
  headerValue: "Bearer ${STATIC_TOKEN}"
```

