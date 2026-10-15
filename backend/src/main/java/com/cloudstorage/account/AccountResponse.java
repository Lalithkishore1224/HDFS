package com.cloudstorage.account;

import java.time.Instant;
import java.util.UUID;

/** An account as shown to its owner (/api/me) and to administrators. Never includes the password hash. */
public record AccountResponse(
        UUID id,
        String username,
        String name,
        AccountRole role,
        AccountStatus status,
        Instant createdAt) {

    public static AccountResponse from(UserAccount account) {
        return new AccountResponse(
                account.getId(),
                account.getUsername(),
                account.getName(),
                account.getRole(),
                account.getStatus(),
                account.getCreatedAt());
    }
}
