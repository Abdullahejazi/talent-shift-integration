FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src src
COPY frontend frontend
RUN mvn -B -ntp clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S talentshift && adduser -S talentshift -G talentshift \
    && mkdir -p /app/uploads && chown -R talentshift:talentshift /app
WORKDIR /app
COPY --from=build /workspace/target/talentshift-core-*.jar app.jar
USER talentshift
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
