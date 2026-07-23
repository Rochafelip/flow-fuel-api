# syntax=docker/dockerfile:1

# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Cache dependencies separately from source for faster rebuilds
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# wget (busybox) is used by the HEALTHCHECK below
RUN addgroup -S app && adduser -S app -G app

COPY --from=build /app/target/*.jar app.jar

USER app

ENV JAVA_OPTS="-XX:+UseSerialGC -XX:MaxRAMPercentage=65 -XX:MaxMetaspaceSize=160m -XX:ReservedCodeCacheSize=48m -Xss512k -XX:TieredStopAtLevel=1"
ENV PORT=10000

EXPOSE 10000

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -q -O- "http://127.0.0.1:${PORT}/actuator/health" || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
