FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B dependency:go-offline
COPY src ./src
RUN ./mvnw -B package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
# Never run as root.
RUN addgroup -S app && adduser -S app -G app
COPY --from=build --chown=app:app /app/target/travel-crm-backend.jar app.jar
# app.storage.local.base-dir defaults to ./uploads, which LocalFileStorageService
# creates at startup. /app is root-owned, so the unprivileged user needs this
# directory made for it. Container-local and wiped on redeploy - set
# STORAGE_PROVIDER=gcs for anything that must survive.
RUN mkdir -p /app/uploads && chown app:app /app/uploads
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
