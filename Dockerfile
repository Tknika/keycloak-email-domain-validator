FROM maven:3.9.9-eclipse-temurin-17

# Set working directory
WORKDIR /app

# Copy project files
COPY pom.xml ./
COPY src ./src

# Default command - can be overridden in docker-compose
CMD ["mvn", "clean", "package"]
