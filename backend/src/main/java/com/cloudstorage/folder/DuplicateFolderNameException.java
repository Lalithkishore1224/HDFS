package com.cloudstorage.folder;

public class DuplicateFolderNameException extends RuntimeException {

    public DuplicateFolderNameException(String name) {
        super("A folder named \"" + name + "\" already exists here");
    }
}
