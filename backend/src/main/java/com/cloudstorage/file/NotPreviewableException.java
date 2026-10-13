package com.cloudstorage.file;

public class NotPreviewableException extends RuntimeException {

    public NotPreviewableException(String fileName) {
        super("\"" + fileName + "\" cannot be previewed; download it instead");
    }
}
