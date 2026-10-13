package com.cloudstorage.storage;

import java.io.InputStream;
import java.util.UUID;

/**
 * Raw byte storage, addressed by file id and tier. Knows nothing about metadata.
 * Implementations could target a local disk, S3 or MinIO.
 */
public interface FileStore {

    /** Writes new content into the hot tier and returns the number of bytes written. */
    long save(UUID id, InputStream content);

    /** Opens the original (uncompressed) content of a file. The caller must close the stream. */
    InputStream open(UUID id, StorageTier tier);

    /** Where the file actually is right now. Metadata can lag behind a move that has not committed yet. */
    StorageTier locate(UUID id);

    /** Size on disk of the file in the given tier. */
    long sizeOf(UUID id, StorageTier tier);

    /** Moves a file between tiers and returns its size on disk in the new tier. */
    long move(UUID id, StorageTier from, StorageTier to);

    /** Removes the file from whichever tier holds it. Deleting something already gone is not an error. */
    void delete(UUID id);
}
