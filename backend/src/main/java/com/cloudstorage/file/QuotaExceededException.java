package com.cloudstorage.file;

public class QuotaExceededException extends RuntimeException {

    public QuotaExceededException(long quotaBytes) {
        super("Storage quota of " + quotaBytes + " bytes would be exceeded");
    }
}
