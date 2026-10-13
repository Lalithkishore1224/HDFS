package com.cloudstorage.insights;

/** Where the numbers come from. A seam so the summary logic can be tested without a database. */
public interface StorageSnapshotProvider {

    StorageSnapshot current();
}
