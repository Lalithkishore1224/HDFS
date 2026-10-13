package com.cloudstorage.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cloudstorage.config.StorageProperties;
import java.io.ByteArrayInputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 5 nodes, 3 copies of every chunk, 1000-byte chunks so that small test files span many chunks. */
class ReplicatedFileStoreTest {

    @TempDir
    Path root;

    ReplicatedFileStore store;

    @BeforeEach
    void setUp() {
        store = new ReplicatedFileStore(new StorageProperties(root, 1 << 30, false, 5, 3, 1000, List.of()));
    }

    private static byte[] content(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }

    private UUID save(byte[] data) {
        UUID id = UUID.randomUUID();
        store.save(id, new ByteArrayInputStream(data));
        return id;
    }

    private byte[] read(UUID id) throws Exception {
        try (var in = store.open(id, store.locate(id))) {
            return in.readAllBytes();
        }
    }

    @Test
    void aFileIsCutIntoChunksAndEveryChunkIsOnThreeDifferentNodes() throws Exception {
        byte[] data = content(40_500, 1);
        UUID id = save(data);

        var placement = store.placementOf(id);
        assertThat(placement.chunks()).hasSize(41);
        assertThat(placement.bytes()).isEqualTo(40_500);
        assertThat(placement.chunks()).allSatisfy(chunk -> assertThat(chunk.nodes()).hasSize(3)
                .extracting(ReplicatedFileStore.NodeRef::name).doesNotHaveDuplicates());
        assertThat(read(id)).isEqualTo(data);
    }

    @Test
    void noNodeHoldsTheWholeFile() {
        UUID id = save(content(40_000, 2));
        for (var node : store.status().nodes()) {
            assertThat(node.chunks()).isLessThan(40);
            assertThat(node.chunks()).isGreaterThan(0);
        }
        assertThat(store.placementOf(id).chunks().size()).isEqualTo(40);
    }

    @Test
    void anyTwoNodesCanFailAtTheSameTime() throws Exception {
        byte[] data = content(30_000, 3);
        UUID id = save(data);
        List<String> names = List.of("node-1", "node-2", "node-3", "node-4", "node-5");
        for (int a = 0; a < names.size(); a++) {
            for (int b = a + 1; b < names.size(); b++) {
                store.setOnline(List.of(names.get(a), names.get(b)), false);   // both at once, no repair in between
                assertThat(read(id)).as("without %s and %s", names.get(a), names.get(b)).isEqualTo(data);
                store.setOnline(List.of(names.get(a), names.get(b)), true);
            }
        }
    }

    @Test
    void failuresOneAfterAnotherAreSurvivedDownToASingleNode() throws Exception {
        byte[] data = content(30_000, 4);
        UUID id = save(data);
        for (String node : List.of("node-1", "node-2", "node-3", "node-4")) {
            store.setOnline(node, false);   // each failure triggers a repair onto the nodes that are left
            assertThat(read(id)).isEqualTo(data);
        }
        assertThat(store.status().nodes()).filteredOn(ReplicatedFileStore.NodeStatus::online).hasSize(1);
    }

    @Test
    void aFileWrittenWhileNodesAreDownIsCompletedWhenTheyReturn() throws Exception {
        store.setOnline("node-1", false);
        store.setOnline("node-2", false);
        byte[] data = content(20_000, 5);
        UUID id = save(data);
        assertThat(read(id)).isEqualTo(data);

        store.setOnline("node-1", true);
        store.setOnline("node-2", true);

        assertThat(store.placementOf(id).chunks()).allSatisfy(chunk -> assertThat(chunk.nodes()).hasSize(3));
        assertThat(store.status().underReplicatedChunks()).isZero();
    }

    @Test
    void addingANodeRebalancesChunksOntoIt() throws Exception {
        byte[] data = content(60_000, 6);
        UUID id = save(data);

        String added = store.addNode();

        var status = store.status();
        assertThat(status.nodes()).hasSize(6);
        assertThat(status.nodes().stream().filter(n -> n.name().equals(added)).findFirst().orElseThrow().chunks())
                .isGreaterThan(0);
        assertThat(store.placementOf(id).chunks()).allSatisfy(chunk -> assertThat(chunk.nodes()).hasSize(3));
        assertThat(read(id)).isEqualTo(data);
    }

    @Test
    void archivingMovesEveryChunkAndRestoreBringsThemBack() throws Exception {
        byte[] data = new byte[20_000];
        Arrays.fill(data, (byte) 'x');
        UUID id = save(data);
        store.move(id, StorageTier.HOT, StorageTier.COLD);
        assertThat(store.locate(id)).isEqualTo(StorageTier.COLD);
        assertThat(store.placementOf(id).chunks()).allMatch(chunk -> chunk.tier() == StorageTier.COLD);
        assertThat(read(id)).isEqualTo(data);
        store.move(id, StorageTier.COLD, StorageTier.HOT);
        assertThat(store.placementOf(id).chunks()).allMatch(chunk -> chunk.tier() == StorageTier.HOT);
    }

    @Test
    void emptyFilesWork() throws Exception {
        UUID id = save(new byte[0]);
        assertThat(read(id)).isEmpty();
    }

    @Test
    void deleteRemovesEveryChunkFromEveryNode() {
        UUID id = save(content(10_000, 7));
        store.delete(id);
        assertThat(store.status().chunks()).isZero();
        assertThatThrownBy(() -> store.locate(id)).isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void lastOnlineNodeCannotBeTakenOffline() {
        for (int i = 1; i <= 4; i++) {
            store.setOnline("node-" + i, false);
        }
        assertThatThrownBy(() -> store.setOnline("node-5", false)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void threeNodesFailingTogetherCanMakeSomeChunksUnavailableUntilTheyReturn() throws Exception {
        byte[] data = content(100_000, 8);   // 100 chunks: some chunk has exactly those three holders
        UUID id = save(data);
        store.setOnline(List.of("node-1", "node-2", "node-3"), false);
        assertThatThrownBy(() -> read(id)).isInstanceOf(UncheckedIOException.class);

        store.setOnline(List.of("node-1", "node-2", "node-3"), true);

        assertThat(read(id)).isEqualTo(data);
    }
}
