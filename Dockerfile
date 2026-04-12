# =============================================================================
# SnelNieuwsApi Dockerfile - Multi-stage build
# =============================================================================

# Stage 1: Builder
FROM eclipse-temurin:17-jdk AS builder

# Install sbt
RUN apt-get update && \
    apt-get install -y curl gnupg2 && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | tee /etc/apt/sources.list.d/sbt.list && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian /" | tee /etc/apt/sources.list.d/sbt_old.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | apt-key add && \
    apt-get update && \
    apt-get install -y sbt && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Cache dependencies
COPY build.sbt /app/
COPY project/build.properties /app/project/
COPY project/plugins.sbt /app/project/
RUN sbt update

# Copy source and build
COPY . /app/
RUN sbt assembly

# Stage 2: Runtime
FROM eclipse-temurin:17-jre AS runtime

LABEL org.opencontainers.image.title="emudoi-snelnieuws-api" \
      org.opencontainers.image.description="SnelNieuws API Service" \
      org.opencontainers.image.vendor="emudoi" \
      com.emudoi.service="emudoi-snelnieuws-api" \
      com.emudoi.environment="production"

# Create non-root user
RUN groupadd -g 1001 emudoi && \
    useradd -u 1001 -g emudoi -s /bin/false emudoi

# Create necessary directories
RUN mkdir -p /var/log/emudoi /opt/emudoi-snelnieuws-api && \
    chown -R emudoi:emudoi /var/log/emudoi /opt/emudoi-snelnieuws-api

WORKDIR /opt/emudoi-snelnieuws-api

# Copy the fat JAR from builder stage
COPY --from=builder /app/target/scala-2.13/emudoi-snelnieuws-api.jar /opt/emudoi-snelnieuws-api/app.jar
RUN chown emudoi:emudoi /opt/emudoi-snelnieuws-api/app.jar

USER emudoi

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -sf http://localhost:8080/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
