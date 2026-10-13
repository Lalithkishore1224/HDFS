package com.cloudstorage.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudstorage.config.StorageProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Nodes as separate HTTP servers: a stopped server is a real failure the cluster must detect and survive. */
class RemoteNodesTest {

    @TempDir
    Path root;

    private final List<HttpServer> servers = new ArrayList<>();
    private final List<Path> dirs = new ArrayList<>();
    private final List<Integer> ports = new ArrayList<>();
    private ReplicatedFileStore store;

    @BeforeEach
    void startCluster() throws Exception {
        List<String> urls = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            Path dir = root.resolve("n" + i);
            HttpServer server = NodeServer.start(dir, 0);
            servers.add(server);
            dirs.add(dir);
            ports.add(server.getAddress().getPort());
            urls.add("http://localhost:" + server.getAddress().getPort());
        }
        store = new ReplicatedFileStore(new StorageProperties(root, 1 << 30, false, 5, 3, 1000, urls));
    }

    @AfterEach
    void stopCluster() {
        servers.forEach(s -> s.stop(0));
    }

    private static byte[] content(int size) {
        byte[] data = new byte[size];
        new Random(42).nextBytes(data);
        return data;
    }

    @Test
    void chunksReachSeparateServersAndComeBack() throws Exception {
        byte[] data = content(25_000);
        UUID id = UUID.randomUUID();
        store.save(id, new ByteArrayInputStream(data));

        assertThat(store.status().separateProcesses()).isTrue();
        assertThat(store.placementOf(id).chunks()).allSatisfy(c -> assertThat(c.nodes()).hasSize(3));
        try (var in = store.open(id, store.locate(id))) {
            assertThat(in.readAllBytes()).isEqualTo(data);
        }
    }

    @Test
    void aStoppedServerIsDetectedAndTwoStoppedServersAreSurvived() throws Exception {
        byte[] data = content(30_000);
        UUID id = UUID.randomUUID();
        store.save(id, new ByteArrayInputStream(data));

        servers.get(0).stop(0);
        servers.get(1).stop(0);
        store.heartbeat();   // what the scheduler does every few seconds

        var status = store.status();
        assertThat(status.nodes().stream().filter(n -> n.state() == StorageNode.State.UNREACHABLE)).hasSize(2);
        try (var in = store.open(id, store.locate(id))) {
            assertThat(in.readAllBytes()).isEqualTo(data);
        }
    }

    @Test
    void aFailedServerIsEvenSurvivedBeforeTheHeartbeatNoticesIt() throws Exception {
        byte[] data = content(30_000);
        UUID id = UUID.randomUUID();
        store.save(id, new ByteArrayInputStream(data));

        servers.get(2).stop(0);   // no heartbeat yet: the first failing request marks the node down

        try (var in = store.open(id, store.locate(id))) {
            assertThat(in.readAllBytes()).isEqualTo(data);
        }
    }

    @Test
    void aNodeThatReturnsIsBroughtUpToDate() throws Exception {
        servers.get(0).stop(0);
        store.heartbeat();
        byte[] data = content(20_000);
        UUID id = UUID.randomUUID();
        store.save(id, new ByteArrayInputStream(data));

        servers.set(0, NodeServer.start(dirs.get(0), ports.get(0)));
        store.heartbeat();

        assertThat(store.status().nodes()).allMatch(n -> n.state() == StorageNode.State.ONLINE);
        assertThat(store.placementOf(id).chunks()).allSatisfy(c -> assertThat(c.nodes()).hasSize(3));
    }
}
