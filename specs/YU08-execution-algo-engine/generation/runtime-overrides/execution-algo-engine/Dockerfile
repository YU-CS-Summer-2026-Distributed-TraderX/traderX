FROM eclipse-temurin:21-jre
WORKDIR /opt/app
COPY build/libs/*.jar app.jar
EXPOSE 18120
ENTRYPOINT ["java", "-jar", "/opt/app/app.jar"]
