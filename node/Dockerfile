# One storage node of the drive: a small HTTP server around the same LocalFileStore the backend uses.
# Build context is the repository root:  docker compose build
FROM eclipse-temurin:21-jdk-alpine
WORKDIR /app
COPY backend/src/main/java/com/cloudstorage/storage/StorageTier.java \
     backend/src/main/java/com/cloudstorage/storage/NodeStore.java \
     backend/src/main/java/com/cloudstorage/storage/LocalFileStore.java \
     backend/src/main/java/com/cloudstorage/storage/NodeServer.java ./src/
RUN javac -d /app/classes src/*.java
EXPOSE 8080
VOLUME /data
CMD ["java", "-cp", "/app/classes", "com.cloudstorage.storage.NodeServer", "/data", "8080"]
