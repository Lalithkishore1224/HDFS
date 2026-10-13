package com.cloudstorage.stats;

import com.cloudstorage.access.AccessLogRepository;
import com.cloudstorage.account.UserAccount;
import com.cloudstorage.config.StorageProperties;
import com.cloudstorage.file.FileRepository;
import com.cloudstorage.file.StoredFile;
import com.cloudstorage.storage.StorageTier;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Numbers behind the storage meter (for users) and the operations page (internal). */
@Service
public class StatsService {

    /**
     * @param logicalBytes     total size of all files as users see it (counts against the quota)
     * @param storedBytes      space actually occupied on disk after cold-tier compression
     * @param coldRestores     downloads that had to wait for a file to be restored from cold storage
     * @param averageResponseMs mean time to start a download, 0 when nothing was downloaded yet
     */
    public record Stats(
            long fileCount,
            long hotFileCount,
            long coldFileCount,
            long logicalBytes,
            long storedBytes,
            long quotaBytes,
            long downloads,
            long coldRestores,
            double averageResponseMs) {
    }

    /** What a user needs for a storage meter: nothing about how the bytes are kept. */
    public record StorageUsage(long usedBytes, long quotaBytes) {
    }

    private final FileRepository files;
    private final AccessLogRepository accessLogs;
    private final StorageProperties properties;

    public StatsService(FileRepository files, AccessLogRepository accessLogs, StorageProperties properties) {
        this.files = files;
        this.accessLogs = accessLogs;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public StorageUsage usage(UserAccount owner) {
        return new StorageUsage(files.totalSizeBytes(owner.getId()), owner.quotaOr(properties.quotaBytes()));
    }

    @Transactional(readOnly = true)
    public Stats current() {
        List<StoredFile> allFiles = files.findAll();
        long hotCount = allFiles.stream().filter(file -> file.getTier() == StorageTier.HOT).count();
        Double averageResponse = accessLogs.averageResponseTimeMs();

        return new Stats(
                allFiles.size(),
                hotCount,
                allFiles.size() - hotCount,
                allFiles.stream().mapToLong(StoredFile::getSizeBytes).sum(),
                allFiles.stream().mapToLong(StoredFile::getStoredSizeBytes).sum(),
                properties.quotaBytes(),
                accessLogs.count(),
                accessLogs.countByServedFromColdTrue(),
                averageResponse == null ? 0 : averageResponse);
    }
}
