# F-042: Java 21 is the long term support release.
# F-044: every base image is pinned by digest, so a build is reproducible.

# Build stage
FROM gradle@sha256:d868117760a7c92214705f47ed173116a5d13e58d68702f974ff30acd062737e AS builder
# gradle:8.13-jdk21
WORKDIR /app
COPY . .
RUN gradle :app:installDist --no-daemon

# Runtime stage
FROM eclipse-temurin@sha256:974b08960c5d96694c780e65b2d5705268ab1e1ca1a0dd0caf4ba6c3fe34d699
# eclipse-temurin:21-jre-alpine
WORKDIR /app

# The base image lags the Alpine security branch, and a released image must not carry a known
# HIGH advisory that a patched package already fixes. This upgrades the TLS packages only, so
# the rest of the image stays exactly as the pinned digest built it. Remove it once the pinned
# Temurin digest ships the fixed packages itself.
RUN apk --no-cache upgrade openssl libssl3 libcrypto3

# Copy built application
COPY --from=builder /app/app/build/install/app .

# Create non-root user
RUN addgroup -S queuebox && adduser -S queuebox -G queuebox

# The default capture state directory. Docker copies this ownership onto an empty named
# volume, so the non-root user can write the capture offsets. Chown a bind mount yourself.
RUN mkdir -p /var/lib/queuebox/capture && chown -R queuebox:queuebox /var/lib/queuebox

USER queuebox

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/health || exit 1

ENTRYPOINT ["./bin/app"]
