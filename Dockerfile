FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
RUN mvn -q -DskipTests dependency:go-offline
COPY src src
RUN mvn -q -DskipTests clean package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN useradd --system --uid 10001 --create-home simulatus \
    && mkdir -p /app/logs \
    && chown -R simulatus:simulatus /app
COPY --from=build /workspace/target/simulatus-backend.jar /app/simulatus-backend.jar
USER simulatus
EXPOSE 8080
VOLUME ["/app/logs"]
ENTRYPOINT ["java","-XX:InitialRAMPercentage=15.0","-XX:MaxRAMPercentage=55.0","-XX:MaxMetaspaceSize=128m","-Dfile.encoding=UTF-8","-jar","/app/simulatus-backend.jar"]
