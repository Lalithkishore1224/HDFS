package com.cloudstorage.storage;

import java.io.InputStream;
import java.util.Set;

/**
 * What a storage node can do with named pieces of data (chunks and manifests). A node is either a
 * directory on this machine ({@link LocalFileStore}) or a separate server process reached over HTTP
 * ({@link RemoteNodeStore}); the cluster logic does not care which.
 */
public interface NodeStore {

    /** Writes new content into the hot tier. */
    void write(String key, byte[] data);

    /** The original (uncompressed) bytes, whichever tier they are in. */
    byte[] readAll(String key);

    InputStream open(String key);

    /** The tier this node holds the key in, or null if it does not hold it. */
    StorageTier tierOf(String key);

    default boolean contains(String key) {
        return tierOf(key) != null;
    }

    /** Size on disk, in whichever tier the key is. */
    long sizeOf(String key);

    /** Moves the key to the target tier (no-op if already there) and returns its size on disk. */
    long move(String key, StorageTier to);

    void delete(String key);

    Set<String> keys();

    long usedBytes();

    /** Copies another node's data for the key, keeping its tier. */
    default void copyFrom(NodeStore source, String key) {
        StorageTier tier = source.tierOf(key);
        if (tier == null) {
            throw new java.io.UncheckedIOException(new java.nio.file.NoSuchFileException(key));
        }
        write(key, source.readAll(key));
        if (tier == StorageTier.COLD) {
            move(key, StorageTier.COLD);
        }
    }
}
