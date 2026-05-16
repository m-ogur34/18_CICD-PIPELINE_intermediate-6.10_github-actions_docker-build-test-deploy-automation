# Multi-stage build — production-ready minimal image

# Stage 1: Build
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app
COPY pom.xml .
COPY src ./src

# Dependency cache layer (pom değişmezse cache hit)
RUN --mount=type=cache,target=/root/.m2 \
    apk add --no-cache maven && \
    mvn -B package -DskipTests --no-transfer-progress

# Extract layered jar (Spring Boot layertools)
RUN java -Djarmode=layertools -jar target/*.jar extract

# Stage 2: Runtime (minimal JRE)
FROM eclipse-temurin:21-jre-alpine AS runtime

# Non-root user — güvenlik
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

WORKDIR /app

# Spring Boot layers — değişme sıklığına göre (cache optimizasyonu)
COPY --from=builder /app/dependencies/ ./
COPY --from=builder /app/spring-boot-loader/ ./
COPY --from=builder /app/snapshot-dependencies/ ./
COPY --from=builder /app/application/ ./

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
  CMD wget -q --spider http://localhost:8080/actuator/health || exit 1

EXPOSE 8080

# JVM tuning for containers
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:+UseG1GC \
               -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
