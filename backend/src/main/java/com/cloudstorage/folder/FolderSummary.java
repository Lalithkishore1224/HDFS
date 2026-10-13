package com.cloudstorage.folder;

import java.util.UUID;

/** The minimum needed to draw a breadcrumb or a folder picker. */
public record FolderSummary(UUID id, String name, UUID parentId) {

    static FolderSummary from(Folder folder) {
        return new FolderSummary(folder.getId(), folder.getName(), folder.getParentId());
    }
}
