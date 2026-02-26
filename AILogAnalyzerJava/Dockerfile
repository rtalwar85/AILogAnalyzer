FROM maven:3.9.9-eclipse-temurin-11 AS build
WORKDIR /workspace

COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -DskipTests package \
 && JAR_PATH="$(find target -maxdepth 1 -type f -name '*.jar' ! -name '*.original' | head -n 1)" \
 && test -n "$JAR_PATH" \
 && cp "$JAR_PATH" target/app.jar

FROM eclipse-temurin:11-jre
WORKDIR /app

ENV SERVER_ADDRESS=0.0.0.0 \
    SERVER_PORT=8080 \
    LOG_ALLOWED_ROOTS=/logs \
    LOG_ANALYZER_CONFIG_FILE=/data/.log-analyzer.config.json

RUN mkdir -p /logs /data

COPY --from=build /workspace/target/app.jar /app/app.jar

EXPOSE 8080
VOLUME ["/logs", "/data"]

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS:-} -jar /app/app.jar"]

