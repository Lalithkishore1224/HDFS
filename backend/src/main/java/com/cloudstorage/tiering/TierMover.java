package com.cloudstorage.tiering;

import com.cloudstorage.storage.FileStore;
import com.cloudstorage.storage.StorageTier;
import java.io.InputStream;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The single place where bytes change tier.
 * <p>
 * A download (restoring a cold file) and the optimizer (archiving a hot file) can ask to move the same
 * file at the same time, so every decision is made from where the bytes really are
 * ({@link FileStore#locate}), never from a database row that may be out of date, and moves are
 * serialized. This is sufficient for one application instance; a multi-instance deployment would need a
 * distributed lock instead.
 * <p>
 * It deliberately knows nothing about the database: callers record the result in their own short
 * transaction, so no transaction is ever held open across slow disk work.
 */
@Component
public class TierMover {

    /**
     * The content of a file, ready to read from the hot tier.
     *
     * @param restoredFromCold true if the file had to be brought back from cold storage first
     * @param storedSizeBytes  size on disk in the hot tier
     */
    public record HotContent(InputStream stream, boolean restoredFromCold, long storedSizeBytes) {
    }

    private record Placement(boolean moved, long sizeBytes) {
    }

    private final FileStore store;

    public TierMover(FileStore store) {
        this.store = store;
    }

    /**
     * Moves the file to the target tier; does nothing if the bytes are already there.
     *
     * @return the file's size on disk in the target tier
     */
    public synchronized long move(UUID fileId, StorageTier target) {
        return place(fileId, target).sizeBytes();
    }

    /**
     * Makes sure the file is in the hot tier and opens it, as one step. Doing both under the same lock
     * means the optimizer cannot archive the file in between.
     */
    public synchronized HotContent openOnHotTier(UUID fileId) {
        Placement placement = place(fileId, StorageTier.HOT);
        return new HotContent(store.open(fileId, StorageTier.HOT), placement.moved(), placement.sizeBytes());
    }

    private Placement place(UUID fileId, StorageTier target) {
        StorageTier actual = store.locate(fileId);
        if (actual == target) {
            return new Placement(false, store.sizeOf(fileId, target));
        }
        return new Placement(true, store.move(fileId, actual, target));
    }
}
