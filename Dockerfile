FROM maven:3.9.9-eclipse-temurin-21 AS builder
WORKDIR /build
COPY . .
RUN --mount=type=cache,target=/root/.m2 set -e; \
      find /root/.m2 -name "*.lastUpdated" -delete; \
      echo "⏳ Compilando con Maven..."; \
      start=$(date +%s); \
      mvn clean package -T 1C -DskipTests -Dquarkus.package.jar.type=uber-jar -Daether.syncContext.named.factory=noop -Dmaven.wagon.http.retryHandler.count=10; \
      end=$(date +%s); \
      echo "⏱️ La compilación tardó $((end - start)) segundos."

FROM registry.access.redhat.com/ubi8/openjdk-21-runtime:latest
WORKDIR /app
COPY --from=builder /build/target/*-runner.jar app.jar
EXPOSE 8086
ENV TZ=UTC
ENV JAVA_OPTS="-Duser.timezone=UTC -Djava.util.logging.manager=org.jboss.logmanager.LogManager"
CMD ["java", "-jar", "app.jar"]
