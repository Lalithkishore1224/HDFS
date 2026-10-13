package com.cloudstorage.file;

import com.cloudstorage.storage.ReplicatedFileStore;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Compares what the storage nodes hold with what the database says exists, and removes the rest.
 * A delete that could not reach a node that was down leaves stale copies on it; without this they
 * would be copied back onto other nodes as if they were live data.
 */
@Component
class StorageReconciler {

    private static final Logger log = LoggerFactory.getLogger(StorageReconciler.class);

    private final FileRepository files;
    private final ReplicatedFileStore store;

    StorageReconciler(FileRepository files, ReplicatedFileStore store) {
        this.files = files;
        this.store = store;
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    void reconcile() {
        Set<UUID> known = files.findAll().stream().map(StoredFile::getId).collect(Collectors.toSet());
        int removed = store.removeOrphans(known);
        if (removed > 0) {
            log.info("Removed {} orphaned storage entries", removed);
        }
    }
}
