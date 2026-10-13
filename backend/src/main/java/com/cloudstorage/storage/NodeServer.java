package com.cloudstorage.storage;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * One storage node as its own process: a tiny HTTP server around a {@link LocalFileStore}. It runs
 * inside a Docker container, so each node has its own filesystem, network address and life cycle.
 * <pre>
 *   java com.cloudstorage.storage.NodeServer /data 8080
 * </pre>
 */
public final class NodeServer {

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private NodeServer() {
    }

    public static void main(String[] args) throws IOException {
        Path data = Path.of(args.length > 0 ? args[0] : "/data");
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
        start(data, port);
        System.out.println("storage node listening on " + port + ", data in " + data);
    }

    /** Starts a node; port 0 picks a free port (see {@code getAddress()}). */
    public static HttpServer start(Path data, int port) throws IOException {
        LocalFileStore store = new LocalFileStore(data);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.createContext("/health", exchange -> respond(exchange, 200, "ok"));
        server.createContext("/keys", exchange -> respond(exchange, 200, String.join("\n", store.keys())));
        server.createContext("/stats", exchange -> respond(exchange, 200, String.valueOf(store.usedBytes())));
        server.createContext("/chunks/", exchange -> {
            try {
                chunk(exchange, store);
            } catch (UncheckedIOException e) {
                respond(exchange, 404, "not found");
            } catch (RuntimeException e) {
                respond(exchange, 500, "error");
            }
        });
        server.start();
        return server;
    }

    private static void chunk(HttpExchange exchange, LocalFileStore store) throws IOException {
        String[] parts = exchange.getRequestURI().getPath().substring("/chunks/".length()).split("/");
        String key = parts[0];
        if (!KEY.matcher(key).matches() || parts.length > 2) {
            respond(exchange, 400, "bad key");
            return;
        }
        String action = parts.length == 2 ? parts[1] : "";
        String method = exchange.getRequestMethod();

        if (action.isEmpty() && method.equals("PUT")) {
            store.write(key, exchange.getRequestBody().readAllBytes());
            respond(exchange, 204, "");
        } else if (action.isEmpty() && method.equals("GET")) {
            if (!store.contains(key)) {
                respond(exchange, 404, "not found");
            } else {
                respond(exchange, 200, store.readAll(key));
            }
        } else if (action.isEmpty() && method.equals("DELETE")) {
            store.delete(key);
            respond(exchange, 204, "");
        } else if (action.equals("info") && method.equals("GET")) {
            StorageTier tier = store.tierOf(key);
            if (tier == null) {
                respond(exchange, 404, "not found");
            } else {
                respond(exchange, 200, tier + " " + store.sizeOf(key));
            }
        } else if (action.equals("move") && method.equals("POST")) {
            String query = exchange.getRequestURI().getQuery();
            StorageTier to = StorageTier.valueOf(query.substring(query.indexOf("to=") + 3));
            respond(exchange, 200, String.valueOf(store.move(key, to)));
        } else {
            respond(exchange, 405, "method not allowed");
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        respond(exchange, status, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        boolean empty = status == 204 || body.length == 0;
        exchange.sendResponseHeaders(status, empty ? -1 : body.length);
        if (!empty) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
        exchange.close();
    }
}
