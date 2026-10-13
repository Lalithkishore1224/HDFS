package com.cloudstorage.account;

import java.time.Instant;
import java.util.UUID;

/** An account as shown to its owner (/api/me) and to administrators. */
public record AccountResponse(
        UUID id,
        String email,
        String name,
        String pictureUrl,
        AccountRole role,
        AccountStatus status,
        Instant createdAt) {

    public static AccountResponse from(UserAccount account) {
        return new AccountResponse(
                account.getId(),
                account.getEmail(),
                account.getName(),
                account.getPictureUrl(),
                account.getRole(),
                account.getStatus(),
                account.getCreatedAt());
    }
}
