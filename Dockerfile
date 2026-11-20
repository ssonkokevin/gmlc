FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml /workspace/pom.xml
COPY gmlc-app/pom.xml /workspace/gmlc-app/pom.xml
COPY gmlc-app/src /workspace/gmlc-app/src
RUN mvn -q -f /workspace/pom.xml -pl gmlc-app -am -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/gmlc-app/target/gmlc.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
