# Запуск pre-built JAR через JRE.
# JAR собирается в CI/CD или локально: mvn clean package -DskipTests
FROM eclipse-temurin:17-jre-alpine

WORKDIR /todo

COPY target/todo-1.jar /todo/todo.jar

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
RUN mkdir -p /todo/logs && chown appuser:appgroup /todo/logs
USER appuser

EXPOSE 8090

ENTRYPOINT ["java", "-jar", "todo.jar"]
