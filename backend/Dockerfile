FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
RUN useradd --system app && mkdir -p /dados/midia && chown app /dados/midia
COPY --from=build /src/target/crm-0.0.1-SNAPSHOT.jar /app.jar
USER app
ENTRYPOINT ["java", "-jar", "/app.jar"]
