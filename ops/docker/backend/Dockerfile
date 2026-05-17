FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /workspace
COPY . .
RUN mvn -pl localmind-core-service -am package -DskipTests

FROM eclipse-temurin:17-jre

WORKDIR /app
COPY --from=build /workspace/localmind-core-service/target/localmind-core-service-*.jar /app/app.jar

EXPOSE 8085

ENTRYPOINT ["java", "-Dspring.profiles.active=docker", "-jar", "/app/app.jar"]
