# ---------- Compilación ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -B package -DskipTests

# ---------- Ejecución (Google Cloud Run) ----------
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --no-create-home --uid 10001 libre
COPY --from=build /app/target/libre-go-1.0.0.jar /app/app.jar
# Perfil de producción: PostgreSQL + cookie segura + chequeos de configuración. Cloud Run define PORT automáticamente.
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
USER libre
EXPOSE 8080
CMD ["java", "-jar", "/app/app.jar"]
