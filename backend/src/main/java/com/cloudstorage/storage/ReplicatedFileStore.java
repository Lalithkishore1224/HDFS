package com.cloudstorage.storage;

import com.cloudstorage.config.StorageProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * A small distributed file store, built the way HDFS, Ceph and Cassandra build theirs:
 * <ul>
 *   <li><b>Chunking.</b> A file is cut into fixed-size chunks; a small manifest records how many.</li>
 *   <li><b>Placement.</b> Each chunk goes to {@code replicas} of the nodes, chosen by rendezvous
 *       hashing: every node gets a score from the chunk's key and the highest scores win. Chunks
 *       therefore spread evenly, no node holds everything, and adding a node moves only the chunks that
 *       now score highest on it.</li>
 *   <li><b>Reads</b> take each chunk from any online node that holds it, so any {@code replicas - 1}
 *       nodes can fail at the same time.</li>
 *   <li><b>Failure detection.</b> Nodes that are separate processes are probed every few seconds
 *       (heartbeat); a node that stops answering is taken out of service automatically.</li>
 *   <li><b>Self-healing.</b> {@link #repair()} runs when a node goes down, returns or is added, and on a
 *       timer. It re-creates missing copies on other online nodes, removes surplus copies and
 *       realigns tiers, so failures that happen one after another are survived as well.</li>
 * </ul>
 * A node is either a directory on this machine or a separate server process (a Docker container) reached
 * over HTTP, depending on {@code storage.node-urls}; both sit behind {@link NodeStore}.
 */
@Component
@Primary
public class ReplicatedFileStore implements FileStore {

    private static final Logger log = LoggerFactory.getLogger(ReplicatedFileStore.class);
    private static final String MANIFEST_SUFFIX = ".manifest";
    /** Bytes that were just written may not be in the database yet, so they are never cleaned up. */
    private static final long RECENT_WRITE_MILLIS = 5 * 60 * 1000;

    private record Manifest(int chunks, long bytes) {
        byte[] toBytes() {
            return ("chunks=" + chunks + "\nbytes=" + bytes + "\n").getBytes(StandardCharsets.UTF_8);
        }

        static Manifest parse(byte[] data) {
            int chunks = 0;
            long bytes = 0;
            for (String line : new String(data, StandardCharsets.UTF_8).split("\n")) {
                if (line.startsWith("chunks=")) {
                    chunks = Integer.parseInt(line.substring(7).trim());
                } else if (line.startsWith("bytes=")) {
                    bytes = Long.parseLong(line.substring(6).trim());
                }
            }
            return new Manifest(chunks, bytes);
        }
    }

    /** One node's state, for the admin panel. */
    public record NodeStatus(String name, StorageNode.State state, boolean online, String endpoint,
                             long chunks, long bytes) {
    }

    /** The cluster at a glance. */
    public record ClusterStatus(int replicationFactor, int nodesThatCanFail, long files, long chunks,
                                long underReplicatedChunks, int chunkBytes, boolean separateProcesses,
                                List<NodeStatus> nodes) {
    }

    /** One copy of a chunk. */
    public record NodeRef(String name, boolean online) {
    }

    /** One chunk of a file: which nodes hold it and in which tier. */
    public record ChunkPlacement(int index, StorageTier tier, long sizeBytes, List<NodeRef> nodes) {
    }

    /** Where every chunk of one file is. */
    public record FilePlacement(long bytes, List<ChunkPlacement> chunks) {
    }

    private final List<StorageNode> nodes = new CopyOnWriteArrayList<>();
    private final Map<UUID, Long> recentWrites = new ConcurrentHashMap<>();
    private final Path root;
    private final int replicas;
    private final int chunkBytes;
    private final boolean separateProcesses;

    public ReplicatedFileStore(StorageProperties properties) {
        List<String> urls = properties.nodeUrls() == null ? List.of() : properties.nodeUrls();
        this.separateProcesses = !urls.isEmpty();
        int count = separateProcesses ? urls.size() : properties.nodes();
        if (count < 1 || properties.replicas() < 1 || properties.replicas() > count || properties.chunkBytes() < 1) {
            throw new IllegalArgumentException(
                    "storage.replicas must be between 1 and the number of nodes, and storage.chunk-bytes positive");
        }
        this.root = properties.root();
        this.replicas = properties.replicas();
        this.chunkBytes = properties.chunkBytes();
        for (int i = 1; i <= count; i++) {
            nodes.add(separateProcesses
                    ? new StorageNode("node-" + i, new RemoteNodeStore(urls.get(i - 1)), urls.get(i - 1))
                    : new StorageNode("node-" + i, new LocalFileStore(root.resolve("node-" + i)), null));
        }
        if (separateProcesses) {
            heartbeat();
        } else {
            adoptWholeFiles();
        }
    }

    // ----- FileStore

    @Override
    public long save(UUID id, InputStream content) {
        recentWrites.put(id, System.currentTimeMillis());
        try {
            long total = 0;
            int index = 0;
            while (true) {
                byte[] chunk = content.readNBytes(chunkBytes);
                if (chunk.length == 0) {
                    break;
                }
                writeReplicated(chunkKey(id, index++), chunk);
                total += chunk.length;
                if (chunk.length < chunkBytes) {
                    break;
                }
            }
            writeReplicated(manifestKey(id), new Manifest(index, total).toBytes());
            recentWrites.put(id, System.currentTimeMillis());
            return total;
        } catch (IOException e) {
            delete(id);
            throw new UncheckedIOException("Cannot save file " + id, e);
        } catch (RuntimeException e) {
            delete(id);
            throw e;
        }
    }

    @Override
    public InputStream open(UUID id, StorageTier tier) {
        Manifest manifest = manifest(id, false);
        for (int i = 0; i < manifest.chunks(); i++) {
            if (onlineHolders(chunkKey(id, i)).isEmpty()) {
                throw missing(id);
            }
        }
        Enumeration<InputStream> chunks = new Enumeration<>() {
            private int next = 0;

            @Override
            public boolean hasMoreElements() {
                return next < manifest.chunks();
            }

            @Override
            public InputStream nextElement() {
                return openChunk(id, next++);
            }
        };
        return new SequenceInputStream(chunks);
    }

    @Override
    public StorageTier locate(UUID id) {
        String key = manifestKey(id);
        for (StorageNode node : onlineHolders(key)) {
            try {
                StorageTier tier = node.store().tierOf(key);
                if (tier != null) {
                    return tier;
                }
            } catch (UncheckedIOException e) {
                suspect(node, e);
            }
        }
        throw missing(id);
    }

    @Override
    public long sizeOf(UUID id, StorageTier tier) {
        Manifest manifest = manifest(id, false);
        long total = 0;   // the manifest is bookkeeping and is not counted
        for (int i = 0; i < manifest.chunks(); i++) {
            String key = chunkKey(id, i);
            total += withHolder(key, node -> node.store().sizeOf(key), id);
        }
        return total;
    }

    @Override
    public synchronized long move(UUID id, StorageTier from, StorageTier to) {
        Manifest manifest = manifest(id, false);
        moveKey(manifestKey(id), to);
        for (int i = 0; i < manifest.chunks(); i++) {
            moveKey(chunkKey(id, i), to);
        }
        return sizeOf(id, to);
    }

    @Override
    public void delete(UUID id) {
        String prefix = id.toString();
        for (StorageNode node : nodes) {
            if (!node.isReachable()) {
                continue;   // its stale copies are removed by removeOrphans once it is back
            }
            try {
                for (String key : node.store().keys()) {
                    if (key.startsWith(prefix)) {
                        node.store().delete(key);
                    }
                }
            } catch (UncheckedIOException e) {
                suspect(node, e);
            }
        }
    }

    // ----- administration

    public ClusterStatus status() {
        Set<String> keys = new HashSet<>();
        List<NodeStatus> statuses = new ArrayList<>();
        for (StorageNode node : nodes) {
            long chunks = 0;
            long bytes = 0;
            if (node.isReachable()) {
                try {
                    Set<String> held = node.store().keys();
                    if (node.isOnline()) {
                        keys.addAll(held);
                    }
                    chunks = held.stream().filter(ReplicatedFileStore::isChunkKey).count();
                    bytes = node.store().usedBytes();
                } catch (UncheckedIOException e) {
                    suspect(node, e);
                }
            }
            statuses.add(new NodeStatus(node.name(), node.state(), node.isOnline(), node.endpoint(), chunks, bytes));
        }
        int target = targetCopies();
        long files = keys.stream().filter(k -> k.endsWith(MANIFEST_SUFFIX)).count();
        long chunks = 0;
        long under = 0;
        int fewest = onlineNodeCount();
        for (String key : keys) {
            if (!isChunkKey(key) && !key.endsWith(MANIFEST_SUFFIX)) {
                continue;
            }
            int copies = onlineHolders(key).size();
            fewest = Math.min(fewest, copies);
            if (isChunkKey(key)) {
                chunks++;
                if (copies < target) {
                    under++;
                }
            }
        }
        return new ClusterStatus(replicas, Math.max(0, fewest - 1), files, chunks, under, chunkBytes,
                separateProcesses, statuses);
    }

    /** Every chunk of one file: which nodes hold it, in which tier, and whether those nodes are online. */
    public FilePlacement placementOf(UUID id) {
        Manifest manifest = manifest(id, true);
        List<ChunkPlacement> chunks = new ArrayList<>();
        for (int i = 0; i < manifest.chunks(); i++) {
            String key = chunkKey(id, i);
            List<NodeRef> holders = new ArrayList<>();
            StorageTier tier = null;
            long size = 0;
            for (StorageNode node : ranking(key)) {
                if (!node.isReachable()) {
                    continue;
                }
                try {
                    StorageTier held = node.store().tierOf(key);
                    if (held != null) {
                        holders.add(new NodeRef(node.name(), node.isOnline()));
                        tier = held;
                        size = node.store().sizeOf(key);
                    }
                } catch (UncheckedIOException e) {
                    suspect(node, e);
                }
            }
            chunks.add(new ChunkPlacement(i, tier, size, holders));
        }
        return new FilePlacement(manifest.bytes(), chunks);
    }

    public void setOnline(String name, boolean online) {
        setOnline(List.of(name), online);
    }

    /**
     * Switches nodes in or out of service (maintenance). Several nodes change together, as in a power or
     * network failure; one repair runs afterwards.
     */
    public void setOnline(List<String> names, boolean online) {
        List<StorageNode> chosen = new ArrayList<>();
        for (String name : names) {
            chosen.add(nodes.stream().filter(n -> n.name().equals(name)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown storage node " + name)));
        }
        if (!online && nodes.stream().filter(n -> n.isOnline() && !chosen.contains(n)).count() < 1) {
            throw new IllegalStateException("At least one storage node must stay online");
        }
        chosen.forEach(node -> node.setOnline(online));
        repair();
    }

    public boolean canAddNodes() {
        return !separateProcesses;
    }

    /** Adds an empty node; repair then moves its share of the chunks onto it. */
    public synchronized String addNode() {
        if (separateProcesses) {
            throw new IllegalStateException("Nodes are separate processes: start another node and list it in storage.node-urls");
        }
        String name = "node-" + (nodes.size() + 1);
        nodes.add(new StorageNode(name, new LocalFileStore(root.resolve(name)), null));
        repair();
        return name;
    }

    /** Probes every node process; a node that stops answering is taken out of service, and repair runs. */
    @Scheduled(fixedDelay = 3_000, initialDelay = 3_000)
    void heartbeat() {
        boolean changed = false;
        for (StorageNode node : nodes) {
            if (node.store() instanceof RemoteNodeStore remote) {
                boolean healthy = remote.isHealthy();
                if (healthy != node.isReachable()) {
                    node.setReachable(healthy);
                    changed = true;
                    log.warn("Storage node {} is now {}", node.name(), healthy ? "reachable" : "UNREACHABLE");
                }
            }
        }
        if (changed) {
            repair();
        }
    }

    /** Runs repair on a timer, so copies are restored even without an administrator pressing the button. */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    void autoRepair() {
        repair();
    }

    /**
     * For every chunk: make sure the nodes that should hold it (the top-scoring online nodes) do, remove
     * copies from other nodes, and make all copies sit in the same tier. Returns copies created.
     */
    public synchronized int repair() {
        Set<String> keys = new HashSet<>();
        for (StorageNode node : nodes) {
            if (node.isOnline()) {
                try {
                    keys.addAll(node.store().keys());
                } catch (UncheckedIOException e) {
                    suspect(node, e);
                }
            }
        }
        int made = 0;
        for (String key : keys) {
            if (isChunkKey(key) || key.endsWith(MANIFEST_SUFFIX)) {
                try {
                    made += repairKey(key);
                } catch (UncheckedIOException e) {
                    log.warn("Could not repair {}: {}", key, e.getMessage());
                }
            }
        }
        return made;
    }

    /**
     * Removes data that belongs to no file the database knows about: leftovers from deletes that could
     * not reach a node that was down, and from uploads that never completed.
     */
    public synchronized int removeOrphans(Set<UUID> knownFiles) {
        long now = System.currentTimeMillis();
        recentWrites.values().removeIf(at -> now - at > RECENT_WRITE_MILLIS);
        int removed = 0;
        for (StorageNode node : nodes) {
            if (!node.isOnline()) {
                continue;
            }
            try {
                for (String key : node.store().keys()) {
                    UUID id = fileIdOf(key);
                    if (id != null && !knownFiles.contains(id) && !recentWrites.containsKey(id)) {
                        node.store().delete(key);
                        removed++;
                    }
                }
            } catch (UncheckedIOException e) {
                suspect(node, e);
            }
        }
        return removed;
    }

    private int repairKey(String key) {
        List<StorageNode> online = ranking(key).stream().filter(StorageNode::isOnline).toList();
        List<StorageNode> holders = new ArrayList<>();
        for (StorageNode node : online) {
            try {
                if (node.store().contains(key)) {
                    holders.add(node);
                }
            } catch (UncheckedIOException e) {
                suspect(node, e);
            }
        }
        if (holders.isEmpty()) {
            return 0;
        }
        int target = Math.min(replicas, online.size());
        List<StorageNode> desired = online.subList(0, Math.min(target, online.size()));
        StorageNode source = holders.get(0);
        int made = 0;
        for (StorageNode node : desired) {
            if (!holders.contains(node) && node.isOnline()) {
                try {
                    node.store().copyFrom(source.store(), key);
                    holders.add(node);
                    made++;
                } catch (UncheckedIOException e) {
                    suspect(node, e);
                }
            }
        }
        for (StorageNode extra : new ArrayList<>(holders)) {
            if (!desired.contains(extra) && holders.size() > target) {
                try {
                    extra.store().delete(key);
                    holders.remove(extra);
                } catch (UncheckedIOException e) {
                    suspect(extra, e);
                }
            }
        }
        StorageTier majority = majorityTier(key, holders);
        for (StorageNode holder : holders) {
            try {
                if (holder.store().tierOf(key) != majority) {
                    holder.store().move(key, majority);
                }
            } catch (UncheckedIOException e) {
                suspect(holder, e);
            }
        }
        return made;
    }

    private static StorageTier majorityTier(String key, List<StorageNode> holders) {
        long hot = 0;
        for (StorageNode holder : holders) {
            try {
                if (holder.store().tierOf(key) == StorageTier.HOT) {
                    hot++;
                }
            } catch (UncheckedIOException ignored) {
                // counted as not hot
            }
        }
        return hot * 2 > holders.size() ? StorageTier.HOT : StorageTier.COLD;
    }

    // ----- placement

    private void writeReplicated(String key, byte[] data) {
        int written = 0;
        for (StorageNode node : ranking(key)) {
            if (written == replicas) {
                break;
            }
            if (node.isOnline()) {
                try {
                    node.store().write(key, data);
                    written++;
                } catch (UncheckedIOException e) {
                    suspect(node, e);
                }
            }
        }
        if (written == 0) {
            throw new UncheckedIOException(new IOException("No storage node is online"));
        }
    }

    /** All nodes, best first: rendezvous hashing of node name and key. */
    private List<StorageNode> ranking(String key) {
        List<StorageNode> ordered = new ArrayList<>(nodes);
        ordered.sort(Comparator.comparingLong((StorageNode n) -> score(n.name(), key)).reversed()
                .thenComparing(StorageNode::name));
        return ordered;
    }

    private static long score(String nodeName, String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest((nodeName + "|" + key).getBytes(StandardCharsets.UTF_8));
            long value = 0;
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | (digest[i] & 0xff);
            }
            return value;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<StorageNode> onlineHolders(String key) {
        List<StorageNode> holders = new ArrayList<>();
        for (StorageNode node : ranking(key)) {
            if (!node.isOnline()) {
                continue;
            }
            try {
                if (node.store().contains(key)) {
                    holders.add(node);
                }
            } catch (UncheckedIOException e) {
                suspect(node, e);
            }
        }
        return holders;
    }

    /** A call to the node failed: treat it as down until its next heartbeat says otherwise. */
    private void suspect(StorageNode node, UncheckedIOException cause) {
        if (node.store() instanceof RemoteNodeStore && node.isReachable()) {
            node.setReachable(false);
            log.warn("Storage node {} failed a request and is treated as down: {}", node.name(), cause.getMessage());
        }
    }

    private interface NodeCall<T> {
        T apply(StorageNode node);
    }

    private <T> T withHolder(String key, NodeCall<T> call, UUID id) {
        for (StorageNode node : onlineHolders(key)) {
            try {
                return call.apply(node);
            } catch (UncheckedIOException e) {
                suspect(node, e);
            }
        }
        throw missing(id);
    }

    private void moveKey(String key, StorageTier to) {
        for (StorageNode node : onlineHolders(key)) {
            try {
                node.store().move(key, to);
            } catch (UncheckedIOException e) {
                suspect(node, e);
            }
        }
    }

    private InputStream openChunk(UUID id, int index) {
        String key = chunkKey(id, index);
        UncheckedIOException failure = null;
        for (StorageNode node : onlineHolders(key)) {
            try {
                return node.store().open(key);
            } catch (UncheckedIOException e) {
                suspect(node, e);
                failure = e;
            }
        }
        throw failure != null ? failure : missing(id);
    }

    private Manifest manifest(UUID id, boolean anyReachableNode) {
        String key = manifestKey(id);
        for (StorageNode node : ranking(key)) {
            boolean usable = anyReachableNode ? node.isReachable() : node.isOnline();
            if (!usable) {
                continue;
            }
            try {
                if (node.store().contains(key)) {
                    return Manifest.parse(node.store().readAll(key));
                }
            } catch (UncheckedIOException e) {
                suspect(node, e);
            }
        }
        throw missing(id);
    }

    private int targetCopies() {
        return Math.min(replicas, onlineNodeCount());
    }

    private int onlineNodeCount() {
        return (int) nodes.stream().filter(StorageNode::isOnline).count();
    }

    private static String chunkKey(UUID id, int index) {
        return id + ".c" + index;
    }

    private static String manifestKey(UUID id) {
        return id + MANIFEST_SUFFIX;
    }

    private static boolean isChunkKey(String key) {
        int dot = key.lastIndexOf(".c");
        return dot == 36 && key.length() > 38 && key.substring(38).chars().allMatch(Character::isDigit);
    }

    private static UUID fileIdOf(String key) {
        if (key.length() < 36) {
            return null;
        }
        try {
            return UUID.fromString(key.substring(0, 36));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static UncheckedIOException missing(UUID id) {
        return new UncheckedIOException(new NoSuchFileException("No stored content for file " + id));
    }

    /**
     * Earlier versions kept each file whole (on one disk, or as one copy per node). Those files are cut
     * into chunks and placed properly the first time this layout starts.
     */
    private void adoptWholeFiles() {
        List<LocalFileStore> sources = new ArrayList<>();
        nodes.forEach(node -> sources.add((LocalFileStore) node.store()));
        if (Files.isDirectory(root.resolve("hot")) || Files.isDirectory(root.resolve("cold"))) {
            sources.add(new LocalFileStore(root));
        }
        Set<UUID> legacy = new HashSet<>();
        for (LocalFileStore source : sources) {
            for (String key : source.keys()) {
                try {
                    legacy.add(UUID.fromString(key));
                } catch (IllegalArgumentException ignored) {
                    // a chunk or manifest of the new layout
                }
            }
        }
        for (UUID id : legacy) {
            String key = id.toString();
            LocalFileStore holder = sources.stream().filter(s -> s.contains(key)).findFirst().orElseThrow();
            StorageTier tier = holder.tierOf(key);
            try (InputStream in = holder.open(key)) {
                save(id, in);
                if (tier == StorageTier.COLD) {
                    move(id, StorageTier.HOT, StorageTier.COLD);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot adopt " + id, e);
            }
            sources.forEach(s -> s.delete(key));
        }
    }
}
