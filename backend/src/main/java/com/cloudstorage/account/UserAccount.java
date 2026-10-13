package com.cloudstorage.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A person who signed in with Google. Created pending; an administrator decides whether they get in. */
@Entity
// Uniqueness of subject and email is declared in schema.sql, next to the other indexes.
@Table(name = "accounts")
public class UserAccount {

    @Id
    private UUID id;

    /** The identity provider's stable id for this person (Google's {@code sub} claim). */
    @Column(nullable = false)
    private String subject;

    @Column(nullable = false)
    private String email;

    private String name;

    @Column(length = 1024)
    private String pictureUrl;

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

    public static UserAccount register(SignInProfile profile, AccountRole role, AccountStatus status, Instant now) {
        UserAccount account = new UserAccount();
        account.id = UUID.randomUUID();
        account.subject = profile.subject();
        account.email = profile.email();
        account.name = profile.name();
        account.pictureUrl = profile.pictureUrl();
        account.role = role;
        account.status = status;
        account.createdAt = now;
        account.decidedAt = status == AccountStatus.APPROVED ? now : null;
        return account;
    }

    public void refreshProfile(SignInProfile profile) {
        this.email = profile.email();
        this.name = profile.name();
        this.pictureUrl = profile.pictureUrl();
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

    public String getEmail() {
        return email;
    }

    public String getName() {
        return name;
    }

    public String getPictureUrl() {
        return pictureUrl;
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
