package com.cloudstorage.account;

/** Where an account stands in the approval process. Only APPROVED accounts may use the drive. */
public enum AccountStatus {
    PENDING,
    APPROVED,
    REJECTED
}
