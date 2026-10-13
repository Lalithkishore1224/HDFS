package com.cloudstorage.file;

import java.util.UUID;

public class StoredFileNotFoundException extends RuntimeException {

    public StoredFileNotFoundException(UUID id) {
        super("File " + id + " does not exist");
    }
}
