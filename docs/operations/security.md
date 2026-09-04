# Operating QueueBox securely

This document covers the transport, the secrets, and the exposed endpoints. Read it before you
put QueueBox on a network that you do not control.

## Transport security

QueueBox listens on plain HTTP. It does not terminate TLS. Put a reverse proxy or an ingress in
front of it, and terminate TLS there. That is the same shape that most Kubernetes deployments
already use, and it keeps certificate rotation out of the application.

Never expose the QueueBox port directly to the internet.

### Kubernetes ingress example

The example terminates TLS at the ingress, sends plain HTTP to the service inside the cluster,
and limits the request body to the same value as `inbox.maxBodyBytes`.

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: queuebox
  annotations:
    cert-manager.io/cluster-issuer: letsencrypt-prod
    nginx.ingress.kubernetes.io/proxy-body-size: 1m
    nginx.ingress.kubernetes.io/ssl-redirect: "true"
spec:
  ingressClassName: nginx
  tls:
    - hosts:
        - webhooks.example.com
      secretName: queuebox-tls
  rules:
    - host: webhooks.example.com
      http:
        paths:
          - path: /inbox
            pathType: Prefix
            backend:
              service:
                name: queuebox
                port:
                  number: 8080
---
apiVersion: v1
kind: Service
metadata:
  name: queuebox
spec:
  selector:
    app: queuebox
  ports:
    - port: 8080
      targetPort: 8080
```

The ingress publishes `/inbox` only. `/metrics`, `/health` and `/admin` stay inside the cluster.

### Nginx example

```nginx
server {
    listen 443 ssl http2;
    server_name webhooks.example.com;

    ssl_certificate     /etc/ssl/certs/queuebox.crt;
    ssl_certificate_key /etc/ssl/private/queuebox.key;
    ssl_protocols       TLSv1.2 TLSv1.3;

    client_max_body_size 1m;

    location /inbox/ {
        proxy_pass http://queuebox:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

### The outbound direction

QueueBox calls an HTTP destination with the scheme that `destinations.<name>.baseUrl` names. Use
`https://` for every destination that leaves your network. `ConfigValidator` refuses a base URL
that is not an absolute HTTP or HTTPS URL.

Set `http.blockPrivateAddresses: true` when the destination configuration comes from a
lower-trust layer. QueueBox then refuses a destination that resolves to a loopback address, a
link-local address, or a private range. That closes the server-side request forgery path to a
cloud metadata endpoint.

## Secrets

Every credential field accepts a `file:` reference. QueueBox reads the file once, at startup, and
removes the trailing newline.

```yaml
database:
  password: file:/run/secrets/queuebox-db-password

sources:
  stripe:
    type: http
    path: /stripe
    idempotencyKeyPath: $.id
    eventTypePath: $.type
    auth:
      type: hmac
      secret: file:/run/secrets/stripe-webhook-secret
```

### The Kubernetes secret pattern

Mount the secret as a file, not as an environment variable. A file cannot be read from
`/proc/<pid>/environ`, and it can be rotated without a restart of the whole pod template.

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: queuebox-secrets
type: Opaque
stringData:
  db-password: the-real-password
  stripe-webhook-secret: whsec_the_real_secret
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: queuebox
spec:
  template:
    spec:
      containers:
        - name: queuebox
          image: ghcr.io/example/queuebox:1.0.0
          env:
            - name: QUEUEBOX_DATABASE_PASSWORD
              value: file:/run/secrets/db-password
          volumeMounts:
            - name: secrets
              mountPath: /run/secrets
              readOnly: true
      volumes:
        - name: secrets
          secret:
            secretName: queuebox-secrets
```

An external secret manager works the same way. Give the operator a file, and point the
configuration at the path.

### Secrets never appear in a log line

Every credential field carries the `Secret` type. Its `toString` returns `Secret(***)`, so a log
line, an exception message, or a crash dump that prints a configuration object cannot leak a
credential. `ConfigSecretTest` loads a configuration that sets every credential field and asserts
that no printed form carries a value.

The outbox publisher also redacts a failed delivery before it stores the reason in `last_error`.
`ErrorSanitizer` masks the value of every known secret-bearing key and truncates the text.

## The admin endpoint

`/admin/transform/test` evaluates a JSONata expression that the caller supplies. That is remote
compute on the host that processes your messages.

- It is disabled by default. Set `admin.enabled: true` to publish it.
- It needs authentication. QueueBox refuses to start with the endpoint enabled and no
  `admin.auth`, unless `admin.insecure: true` is set on purpose.
- The caller-supplied timeout is clamped to `admin.maxTransformTimeoutMs`, default 1000
  milliseconds. The payload is clamped to `admin.maxPayloadBytes`.

Never publish `/admin` through the ingress.

## Request limits

| Setting | Default | Purpose |
|---------|---------|---------|
| `inbox.maxBodyBytes` | 1048576 | The largest accepted request body. A larger body gets 413. |
| `sources.<name>.rateLimit.requestsPerMinute` | not set | The request rate that one source accepts. A caller over the limit gets 429 with `Retry-After`. |
| `http.maxErrorBodyBytes` | 2048 | The largest error body that a failed delivery keeps. |

The rate limit uses one bucket per source. It protects the database from one busy source. It does
not isolate one caller from another, so keep the per-client control at the ingress.
