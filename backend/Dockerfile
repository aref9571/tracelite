FROM maven:3.9.11-eclipse-temurin-21-noble AS build
WORKDIR /workspace
COPY pom.xml ./
COPY backend/pom.xml backend/
COPY backend/src/main backend/src/main
RUN --mount=type=cache,target=/root/.m2 \
    mvn --batch-mode --no-transfer-progress --projects backend --also-make package -DskipTests \
    && java -Djarmode=tools -jar backend/target/tracelite-backend.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:21-jre-noble
RUN groupadd --system tracelite \
    && useradd --system --gid tracelite --no-create-home --shell /usr/sbin/nologin tracelite
WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER tracelite
ENV SERVER_PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=3 \
    CMD ["curl", "--fail", "--silent", "--output", "/dev/null", "http://localhost:8080/actuator/health/readiness"]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
