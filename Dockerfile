# F-042: Java 21 is the long term support release.
# F-044: every base image is pinned by digest, so a build is reproducible.

# Build stage
FROM gradle@sha256:67b8c4bfd2b064e58a7307e2da1fc3881bc03ecc7a57cf61d8b570a02ebfaea2 AS builder
# gradle:8.13-jdk21
WORKDIR /app
COPY . .
RUN gradle :app:installDist --no-daemon

# Runtime stage
FROM eclipse-temurin@sha256:974b08960c5d96694c780e65b2d5705268ab1e1ca1a0dd0caf4ba6c3fe34d699
# eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy built application
COPY --from=builder /app/app/build/install/app .

# Create non-root user
RUN addgroup -S queuebox && adduser -S queuebox -G queuebox
USER queuebox

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/health || exit 1

ENTRYPOINT ["./bin/app"]
