package com.cloudstorage.file;

import com.cloudstorage.storage.StorageTier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Metadata of one uploaded file. The bytes themselves live in a {@code FileStore}. */
@Entity
@Table(name = "stored_files")
public class StoredFile {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    /** The account that owns this file; nobody else can see or change it. */
    private UUID ownerId;

    /** The folder holding this file; null means the top level of the drive. */
    private UUID folderId;

    private String contentType;

    /** Original size of the content. Counts against the quota. */
    private long sizeBytes;

    /** Size currently occupied on disk; smaller than {@link #sizeBytes} once compressed in the cold tier. */
    private long storedSizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StorageTier tier;

    @Column(nullable = false)
    private Instant uploadedAt;

    @Column(nullable = false)
    private Instant lastAccessedAt;

    private int accessCount;

    /** The optimizer's latest estimate that this file is opened within the next week; null until first run. */
    private Double hotProbability;

    protected StoredFile() {
        // required by JPA
    }

    public static StoredFile newUpload(UUID id, UUID ownerId, String name, UUID folderId, String contentType, long sizeBytes, Instant now) {
        StoredFile file = new StoredFile();
        file.id = id;
        file.ownerId = ownerId;
        file.name = name;
        file.folderId = folderId;
        file.contentType = contentType;
        file.sizeBytes = sizeBytes;
        file.storedSizeBytes = sizeBytes;
        file.tier = StorageTier.HOT;
        file.uploadedAt = now;
        file.lastAccessedAt = now;
        return file;
    }

    public void rename(String newName) {
        this.name = newName;
    }

    public void moveToFolder(UUID newFolderId) {
        this.folderId = newFolderId;
    }

    public void recordAccess(Instant when) {
        this.lastAccessedAt = when;
        this.accessCount++;
    }

    public void placeIn(StorageTier newTier, long newStoredSizeBytes) {
        this.tier = newTier;
        this.storedSizeBytes = newStoredSizeBytes;
    }

    public void updateHotProbability(double probability) {
        this.hotProbability = probability;
    }

    /** Overwrites the usage history. Used only to seed demo data with a realistic past. */
    public void restoreHistory(Instant uploadedAt, Instant lastAccessedAt, int accessCount) {
        this.uploadedAt = uploadedAt;
        this.lastAccessedAt = lastAccessedAt;
        this.accessCount = accessCount;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public UUID getFolderId() {
        return folderId;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public long getStoredSizeBytes() {
        return storedSizeBytes;
    }

    public StorageTier getTier() {
        return tier;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    public Instant getLastAccessedAt() {
        return lastAccessedAt;
    }

    public int getAccessCount() {
        return accessCount;
    }

    public Double getHotProbability() {
        return hotProbability;
    }
}
