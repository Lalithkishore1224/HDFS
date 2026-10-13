package com.cloudstorage.insights;

import java.time.Instant;

/**
 * The only information that ever leaves the server for the AI: aggregate numbers.
 * There is deliberately no field for a file name, folder name, email or any content.
 *
 * @param lastRunAt null if the optimizer has not run since the server started
 */
public record StorageSnapshot(
        long fileCount,
        long hotFileCount,
        long coldFileCount,
        long logicalBytes,
        long storedBytes,
        long downloads,
        long coldRestores,
        double averageResponseMs,
        double optimizerThreshold,
        Instant lastRunAt,
        int lastRunMovedToCold,
        int lastRunMovedToHot) {

    public long savedBytes() {
        return Math.max(0, logicalBytes - storedBytes);
    }

    public double savedPercent() {
        return logicalBytes == 0 ? 0 : 100.0 * savedBytes() / logicalBytes;
    }

    public double coldRestorePercent() {
        return downloads == 0 ? 0 : 100.0 * coldRestores / downloads;
    }

    public boolean optimizerHasRun() {
        return lastRunAt != null;
    }
}
