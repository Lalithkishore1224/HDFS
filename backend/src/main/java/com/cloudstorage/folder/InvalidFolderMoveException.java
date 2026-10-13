package com.cloudstorage.folder;

public class InvalidFolderMoveException extends RuntimeException {

    public InvalidFolderMoveException() {
        super("A folder cannot be moved into itself or one of its subfolders");
    }
}
