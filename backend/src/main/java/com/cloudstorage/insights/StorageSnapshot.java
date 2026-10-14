package com.cloudstorage.insights;

import java.time.Instant;

/**
 * The only information that ever leaves the server for the AI: aggregate numbers.
 * There is deliberately no field for a file name, folder name, email or any content.
 *
 * @param lastRunAt          null if the optimizer has not run since the server started
 * @param storageNodes       how many storage nodes the cluster has (0 when unknown)
 * @param nodesOnline        how many of them are serving
 * @param replicationFactor  copies kept of every chunk
 * @param chunks             chunks stored (each file is cut into chunks)
 * @param underReplicatedChunks chunks that currently have fewer copies than wanted
 * @param nodesThatCanFail   how many more nodes could fail with every file still readable
 * @param accounts           number of accounts (0 when unknown)
 * @param pendingAccounts    accounts waiting for approval
 * @param quotaBytesPerAccount the default storage allowance of one account
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
        int lastRunMovedToHot,
        int storageNodes,
        int nodesOnline,
        int replicationFactor,
        long chunks,
        long underReplicatedChunks,
        int nodesThatCanFail,
        long accounts,
        long pendingAccounts,
        long quotaBytesPerAccount) {

    /** A snapshot without cluster or account information. */
    public StorageSnapshot(long fileCount, long hotFileCount, long coldFileCount, long logicalBytes, long storedBytes,
                           long downloads, long coldRestores, double averageResponseMs, double optimizerThreshold,
                           Instant lastRunAt, int lastRunMovedToCold, int lastRunMovedToHot) {
        this(fileCount, hotFileCount, coldFileCount, logicalBytes, storedBytes, downloads, coldRestores,
                averageResponseMs, optimizerThreshold, lastRunAt, lastRunMovedToCold, lastRunMovedToHot,
                0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public long savedBytes() {
        return Math.max(0, logicalBytes - storedBytes);
    }

    public double savedPercent() {
        return logicalBytes == 0 ? 0 : 100.0 * savedBytes() / logicalBytes;
    }

    public double coldRestorePercent() {
        return downloads == 0 ? 0 : 100.0 * coldRestores / downloads;
    }

    public double coldFilePercent() {
        return fileCount == 0 ? 0 : 100.0 * coldFileCount / fileCount;
    }

    public long averageFileBytes() {
        return fileCount == 0 ? 0 : logicalBytes / fileCount;
    }

    public boolean optimizerHasRun() {
        return lastRunAt != null;
    }

    public boolean clusterKnown() {
        return storageNodes > 0;
    }

    public boolean accountsKnown() {
        return accounts > 0;
    }
}
