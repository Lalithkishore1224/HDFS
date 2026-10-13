package com.cloudstorage.storage;

/**
 * HOT files are stored as-is and served immediately.
 * COLD files are compressed to save space and must be restored before they can be served.
 */
public enum StorageTier {
    HOT,
    COLD
}
