package com.cloudstorage.folder;

import java.util.Collection;
import java.util.UUID;

/**
 * What the folder tree needs to know about the files stored in it.
 * <p>
 * Declared here and implemented by the file module, so folders depend on an abstraction
 * rather than on files, and the two modules do not depend on each other in a cycle.
 */
public interface FolderContents {

    long countFilesIn(UUID folderId);

    /** Permanently deletes every file stored directly in any of the given folders. */
    void deleteFilesIn(Collection<UUID> folderIds);
}
