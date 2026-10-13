package com.cloudstorage.folder;

import java.time.Instant;
import java.util.UUID;

/**
 * A folder as shown in a listing.
 *
 * @param itemCount number of files plus direct subfolders inside it
 */
public record FolderResponse(UUID id, String name, UUID parentId, Instant createdAt, long itemCount) {
}
