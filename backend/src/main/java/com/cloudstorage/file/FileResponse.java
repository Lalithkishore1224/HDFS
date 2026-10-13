package com.cloudstorage.file;

import java.time.Instant;
import java.util.UUID;

/** The JSON shape of a file as seen by users. Storage tiers are deliberately not part of it. */
public record FileResponse(
        UUID id,
        String name,
        UUID folderId,
        String contentType,
        long sizeBytes,
        Instant uploadedAt,
        Instant lastAccessedAt) {

    public static FileResponse from(StoredFile file) {
        return new FileResponse(
                file.getId(),
                file.getName(),
                file.getFolderId(),
                file.getContentType(),
                file.getSizeBytes(),
                file.getUploadedAt(),
                file.getLastAccessedAt());
    }
}
