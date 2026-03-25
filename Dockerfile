# Build stage
FROM sbtscala/scala-sbt:eclipse-temurin-17.0.4_1.7.1_2.13.10 AS builder

WORKDIR /app

# Copy build files
COPY build.sbt .
COPY project/ project/

# Download dependencies
RUN sbt update

# Copy source code
COPY src/ src/

# Build the fat JAR
RUN sbt assembly

# Runtime stage
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Copy the fat JAR from builder
COPY --from=builder /app/target/scala-2.13/snelnieuws-api.jar .

# Create webapp directory structure
RUN mkdir -p src/main/webapp/WEB-INF

EXPOSE 8080

ENV PORT=8080

CMD ["java", "-jar", "snelnieuws-api.jar"]
