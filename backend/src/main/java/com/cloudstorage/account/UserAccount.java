package com.cloudstorage.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A person with a username and password. Created pending; an administrator decides whether they get in. */
@Entity
// Uniqueness of subject and email is declared in schema.sql, next to the other indexes.
@Table(name = "accounts")
public class UserAccount {

    @Id
    private UUID id;

    /** The stable identity the session carries: {@code user:<username>} (older accounts keep what they had). */
    @Column(nullable = false)
    private String subject;

    /** What the person types to sign in; lower-case, unique. Null only for accounts that predate passwords. */
    private String username;

    /** BCrypt hash of the password. Null for an account that has never had one set (see claiming rules). */
    private String passwordHash;

    /** Kept for accounts created before username sign-in; not used for new ones. */
    private String email;

    /** What is shown in the app. */
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    /** When an administrator last approved or rejected the account; null while pending. */
    private Instant decidedAt;

    /** Per-account storage limit; null means the system default applies. */
    private Long quotaBytes;

    protected UserAccount() {
        // required by JPA
    }

    public static UserAccount create(String username, String displayName, String passwordHash, AccountRole role,
                                     AccountStatus status, Instant now) {
        UserAccount account = new UserAccount();
        account.id = UUID.randomUUID();
        account.subject = "user:" + username;
        account.username = username;
        account.passwordHash = passwordHash;
        account.name = displayName;
        account.role = role;
        account.status = status;
        account.createdAt = now;
        account.decidedAt = status == AccountStatus.APPROVED ? now : null;
        return account;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public boolean hasPassword() {
        return passwordHash != null;
    }

    public void promoteToAdmin(Instant now) {
        this.role = AccountRole.ADMIN;
        approve(now);
    }

    public void approve(Instant now) {
        this.status = AccountStatus.APPROVED;
        this.decidedAt = now;
    }

    public void reject(Instant now) {
        this.status = AccountStatus.REJECTED;
        this.decidedAt = now;
    }

    public boolean isApproved() {
        return status == AccountStatus.APPROVED;
    }

    public boolean isAdmin() {
        return role == AccountRole.ADMIN;
    }

    public long quotaOr(long defaultQuotaBytes) {
        return quotaBytes != null ? quotaBytes : defaultQuotaBytes;
    }

    public UUID getId() {
        return id;
    }

    public String getSubject() {
        return subject;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getName() {
        return name;
    }

    public AccountRole getRole() {
        return role;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}
