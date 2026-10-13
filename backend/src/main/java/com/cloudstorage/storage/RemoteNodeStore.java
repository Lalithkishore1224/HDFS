package com.cloudstorage.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** A storage node running as its own server process (see {@link NodeServer}), reached over HTTP. */
public class RemoteNodeStore implements NodeStore {

    private final String baseUrl;
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public RemoteNodeStore(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** A quick liveness probe; any failure to answer means the node is down. */
    public boolean isHealthy() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void write(String key, byte[] data) {
        HttpResponse<byte[]> response = send(
                HttpRequest.newBuilder(uri("/chunks/" + key)).timeout(Duration.ofSeconds(30))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(data)).build());
        require(response, 200, 204);
    }

    @Override
    public byte[] readAll(String key) {
        HttpResponse<byte[]> response = send(get("/chunks/" + key));
        require(response, 200);
        return response.body();
    }

    @Override
    public InputStream open(String key) {
        return new ByteArrayInputStream(readAll(key));
    }

    @Override
    public StorageTier tierOf(String key) {
        HttpResponse<byte[]> response = send(get("/chunks/" + key + "/info"));
        if (response.statusCode() == 404) {
            return null;
        }
        require(response, 200);
        return StorageTier.valueOf(info(response).split(" ")[0]);
    }

    @Override
    public long sizeOf(String key) {
        HttpResponse<byte[]> response = send(get("/chunks/" + key + "/info"));
        require(response, 200);
        return Long.parseLong(info(response).split(" ")[1]);
    }

    @Override
    public long move(String key, StorageTier to) {
        HttpResponse<byte[]> response = send(
                HttpRequest.newBuilder(uri("/chunks/" + key + "/move?to=" + to)).timeout(Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.noBody()).build());
        require(response, 200);
        return Long.parseLong(new String(response.body(), StandardCharsets.UTF_8).trim());
    }

    @Override
    public void delete(String key) {
        HttpResponse<byte[]> response = send(
                HttpRequest.newBuilder(uri("/chunks/" + key)).timeout(Duration.ofSeconds(10)).DELETE().build());
        require(response, 200, 204);
    }

    @Override
    public Set<String> keys() {
        HttpResponse<byte[]> response = send(get("/keys"));
        require(response, 200);
        String body = new String(response.body(), StandardCharsets.UTF_8).trim();
        return body.isEmpty() ? new HashSet<>() : new HashSet<>(Arrays.asList(body.split("\n")));
    }

    @Override
    public long usedBytes() {
        HttpResponse<byte[]> response = send(get("/stats"));
        require(response, 200);
        return Long.parseLong(new String(response.body(), StandardCharsets.UTF_8).trim());
    }

    private HttpRequest get(String path) {
        return HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10)).GET().build();
    }

    private URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    private HttpResponse<byte[]> send(HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException("Storage node " + baseUrl + " did not answer", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new IOException("interrupted"));
        }
    }

    private static String info(HttpResponse<byte[]> response) {
        // "HOT 1234"
        return new String(response.body(), StandardCharsets.UTF_8).trim();
    }

    private void require(HttpResponse<byte[]> response, int... accepted) {
        for (int status : accepted) {
            if (response.statusCode() == status) {
                return;
            }
        }
        throw new UncheckedIOException(new IOException(
                "Storage node " + baseUrl + " answered " + response.statusCode()));
    }
}
