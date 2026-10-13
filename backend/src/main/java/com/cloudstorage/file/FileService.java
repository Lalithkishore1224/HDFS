package com.cloudstorage.file;

import com.cloudstorage.access.AccessLog;
import com.cloudstorage.access.AccessLogRepository;
import com.cloudstorage.account.UserAccount;
import com.cloudstorage.config.StorageProperties;
import com.cloudstorage.folder.FolderContents;
import com.cloudstorage.folder.FolderNotFoundException;
import com.cloudstorage.folder.FolderRepository;
import com.cloudstorage.storage.FileStore;
import com.cloudstorage.storage.StorageTier;
import com.cloudstorage.tiering.TierMover;
import java.io.InputStream;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** The use cases of the drive: upload, list, download, rename, move and delete. */
@Service
public class FileService implements FolderContents {

    private final FileRepository files;
    private final FolderRepository folders;
    private final AccessLogRepository accessLogs;
    private final FileStore store;
    private final TierMover tierMover;
    private final StorageProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public FileService(FileRepository files, FolderRepository folders, AccessLogRepository accessLogs, FileStore store,
                       TierMover tierMover, StorageProperties properties, Clock clock,
                       PlatformTransactionManager transactionManager) {
        this.files = files;
        this.folders = folders;
        this.accessLogs = accessLogs;
        this.store = store;
        this.tierMover = tierMover;
        this.properties = properties;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** A file together with a stream of its original content. The caller must close the stream. */
    public record Download(StoredFile file, InputStream content) {
    }

    /**
     * Without search text this lists the contents of one folder (the top level when
     * {@code folderId} is null). Searching looks across the whole drive.
     */
    @Transactional(readOnly = true)
    public List<StoredFile> list(UserAccount owner, String nameFragment, UUID folderId) {
        if (!nameFragment.isBlank()) {
            return files.findByOwnerIdAndNameContainingIgnoreCaseOrderByUploadedAtDesc(owner.getId(), nameFragment);
        }
        return folderId == null
                ? files.findByOwnerIdAndFolderIdIsNullOrderByNameAsc(owner.getId())
                : files.findByOwnerIdAndFolderIdOrderByNameAsc(owner.getId(), folderId);
    }

    /**
     * Not transactional on purpose: writing a large upload to disk can take seconds, and a database
     * transaction held open that long would hold a database connection and its locks for nothing.
     * The bytes are written first, then one short transaction records them; if recording fails the
     * bytes are removed again so nothing is left orphaned.
     */
    public StoredFile upload(UserAccount owner, String originalName, UUID folderId, String contentType, long sizeBytes,
                             InputStream content) {
        requireFolderExists(owner, folderId);
        long quota = owner.quotaOr(properties.quotaBytes());
        if (files.totalSizeBytes(owner.getId()) + sizeBytes > quota) {
            throw new QuotaExceededException(quota);
        }
        UUID id = UUID.randomUUID();
        long writtenBytes = store.save(id, content);
        try {
            return files.save(StoredFile.newUpload(
                    id, owner.getId(), plainFileName(originalName), folderId, contentType, writtenBytes, clock.instant()));
        } catch (RuntimeException e) {
            store.delete(id);
            throw e;
        }
    }

    /** Looks up one of the owner's files without recording an access. */
    @Transactional(readOnly = true)
    public StoredFile get(UserAccount owner, UUID id) {
        return find(owner, id);
    }

    /**
     * Prepares a download. A cold file is restored to the hot tier first, so the response time
     * recorded here is exactly the price of having archived a file that was still needed.
     */
    public Download download(UserAccount owner, UUID id) {
        long startedAt = System.nanoTime();
        StoredFile file = find(owner, id);

        // Restoring may decompress a large file: do that with no database transaction open.
        TierMover.HotContent hot = tierMover.openOnHotTier(file.getId());

        Instant now = clock.instant();
        long responseTimeMs = (System.nanoTime() - startedAt) / 1_000_000;
        transactions.executeWithoutResult(status -> files.findById(id).ifPresent(current -> {
            current.placeIn(StorageTier.HOT, hot.storedSizeBytes());
            current.recordAccess(now);
            accessLogs.save(new AccessLog(id, now, responseTimeMs, hot.restoredFromCold()));
        }));
        return new Download(file, hot.stream());
    }

    @Transactional
    public StoredFile rename(UserAccount owner, UUID id, String newName) {
        StoredFile file = find(owner, id);
        file.rename(plainFileName(newName));
        return file;
    }

    /** Moves a file into another folder, or to the top level when {@code folderId} is null. */
    @Transactional
    public StoredFile move(UserAccount owner, UUID id, UUID folderId) {
        requireFolderExists(owner, folderId);
        StoredFile file = find(owner, id);
        file.moveToFolder(folderId);
        return file;
    }

    @Override
    @Transactional(readOnly = true)
    public long countFilesIn(UUID folderId) {
        return files.countByFolderId(folderId);
    }

    @Override
    @Transactional
    public void deleteFilesIn(Collection<UUID> folderIds) {
        folderIds.forEach(folderId -> files.findByFolderIdOrderByNameAsc(folderId).forEach(this::remove));
    }

    @Transactional
    public void delete(UserAccount owner, UUID id) {
        remove(find(owner, id));
    }

    private void remove(StoredFile file) {
        store.delete(file.getId());
        accessLogs.deleteByFileId(file.getId());
        files.delete(file);
    }

    private void requireFolderExists(UserAccount owner, UUID folderId) {
        if (folderId != null && folders.findByIdAndOwnerId(folderId, owner.getId()).isEmpty()) {
            throw new FolderNotFoundException(folderId);
        }
    }

    private StoredFile find(UserAccount owner, UUID id) {
        return files.findByIdAndOwnerId(id, owner.getId()).orElseThrow(() -> new StoredFileNotFoundException(id));
    }

    /** Drops any directory part a client may have sent along with the file name. */
    private static String plainFileName(String name) {
        String plain = Paths.get(name.replace('\\', '/')).getFileName().toString().strip();
        return plain.isEmpty() ? "untitled" : plain;
    }
}
