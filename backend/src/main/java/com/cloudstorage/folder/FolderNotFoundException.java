package com.cloudstorage.folder;

import java.util.UUID;

public class FolderNotFoundException extends RuntimeException {

    public FolderNotFoundException(UUID id) {
        super("Folder " + id + " does not exist");
    }
}
