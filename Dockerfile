# ========================================
# СТАДИЯ 1: Сборка приложения
# ========================================
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

COPY . .

# -Dvaadin.productionMode — ускоряет сборку фронтенда
# -DskipTests — пропускаем тесты для ускорения
RUN mvn clean package -DskipTests -Dvaadin.productionMode \
    -Dorg.slf4j.simpleLogger.log.org.apache.maven.cli.transfer.Slf4jMavenTransferListener=warn

# ========================================
# СТАДИЯ 2: Финальный образ
# ========================================
FROM eclipse-temurin:17-jre-alpine

WORKDIR /todo


COPY --from=builder /build/target/todo-1.jar /todo/todo.jar

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

EXPOSE 8090

ENTRYPOINT ["java", "-jar", "todo.jar"]