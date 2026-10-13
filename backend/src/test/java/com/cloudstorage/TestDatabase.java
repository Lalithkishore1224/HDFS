package com.cloudstorage;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Gives each test class its own empty database on a real PostgreSQL server, plus its own file storage.
 * <p>
 * One server is started for the whole test run (a few seconds, no Docker needed) and every test class
 * gets a fresh database on it, so tests stay isolated and the server start-up cost is paid once.
 */
public final class TestDatabase {

    private static final Object LOCK = new Object();
    private static EmbeddedPostgres server;

    private TestDatabase() {
    }

    public static void register(DynamicPropertyRegistry registry) {
        try {
            String database = "test_" + UUID.randomUUID().toString().replace("-", "");
            EmbeddedPostgres postgres = server();
            try (Connection admin = postgres.getPostgresDatabase().getConnection(); Statement statement = admin.createStatement()) {
                statement.execute("CREATE DATABASE " + database);
            }
            Path storage = Files.createTempDirectory("cloud-storage-test");
            registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", database));
            registry.add("spring.datasource.username", () -> "postgres");
            registry.add("spring.datasource.password", () -> "postgres");
            registry.add("storage.root", () -> storage.resolve("files").toString());
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Cannot prepare a test database", e);
        }
    }

    private static EmbeddedPostgres server() throws IOException {
        synchronized (LOCK) {
            if (server == null) {
                server = EmbeddedPostgres.builder().start();
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        server.close();
                    } catch (IOException ignored) {
                        // shutting down anyway
                    }
                }));
            }
            return server;
        }
    }
}
