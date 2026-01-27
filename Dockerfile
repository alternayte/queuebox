# Build stage
FROM gradle:8.11-jdk23 AS builder
WORKDIR /app
COPY . .
RUN gradle :app:installDist --no-daemon

# Runtime stage
FROM eclipse-temurin:23-jre-alpine
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
