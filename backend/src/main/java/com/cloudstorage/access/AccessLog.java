package com.cloudstorage.access;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One download of one file. This is the raw material the optimizer learns from. */
@Entity
@Table(name = "access_logs", indexes = @Index(columnList = "fileId"))
public class AccessLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID fileId;

    @Column(nullable = false)
    private Instant accessedAt;

    /** Time spent preparing the download, including restoring the file from cold storage. */
    private long responseTimeMs;

    private boolean servedFromCold;

    protected AccessLog() {
        // required by JPA
    }

    public AccessLog(UUID fileId, Instant accessedAt, long responseTimeMs, boolean servedFromCold) {
        this.fileId = fileId;
        this.accessedAt = accessedAt;
        this.responseTimeMs = responseTimeMs;
        this.servedFromCold = servedFromCold;
    }

    public UUID getFileId() {
        return fileId;
    }

    public Instant getAccessedAt() {
        return accessedAt;
    }
}
