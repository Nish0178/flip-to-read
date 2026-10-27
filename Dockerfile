# ==============================================================================
# Stage 1: Build Spring Boot Application via Maven
# ==============================================================================
FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /build

# Copy Maven wrapper and module POMs
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY java-app/pom.xml java-app/

RUN chmod +x ./mvnw

# Download dependencies for layer caching
RUN ./mvnw dependency:go-offline -B -pl java-app || true

# Copy source code and build executable jar
ARG BUILD_DATE=unknown
COPY java-app/src java-app/src
RUN ./mvnw clean package -DskipTests -B

# ==============================================================================
# Stage 2: Production Runtime (Java 21 + Python 3.10-3.12 + PyMuPDF)
# ==============================================================================
FROM eclipse-temurin:21-jre-jammy AS runner

# Install Python 3 (3.10 on Ubuntu Jammy), pip, and curl for health check
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        python3 \
        python3-pip \
        curl && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Install pinned Python dependencies
COPY python-service/requirements.txt python-service/
RUN pip3 install --default-timeout=120 --retries=5 --no-cache-dir -r python-service/requirements.txt

# Copy Python converter script
COPY python-service/convert.py python-service/

# Copy compiled Spring Boot JAR
COPY --from=builder /build/java-app/target/pdf-flipbook-1.0.0.jar app.jar

# Setup non-root user and temporary writable storage
RUN groupadd -r appgroup && \
    useradd -r -g appgroup -d /app -s /sbin/nologin appuser && \
    mkdir -p /app/storage/books && \
    chown -R appuser:appgroup /app

# Environment configuration
ENV PORT=8080 \
    PYTHON_CMD=python3 \
    PYTHON_SCRIPT=python-service/convert.py \
    STORAGE_DIR=/app/storage/books \
    APP_DPI=180 \
    APP_IMAGE_FORMAT=jpg \
    MAX_FILE_SIZE=50MB \
    MAX_REQUEST_SIZE=50MB \
    CORS_ALLOWED_ORIGINS=""

EXPOSE 8080

USER appuser

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD curl -f http://localhost:${PORT}/ || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
