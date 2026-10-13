package com.cloudstorage.account;

import com.cloudstorage.config.AuthProperties;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account creation and approval.
 * <p>
 * First sign-in creates a PENDING account, unless the email is configured as an administrator.
 * Pending accounts can sign in but cannot use the drive until an administrator approves them.
 */
@Service
public class AccountService {

    private final UserRepository users;
    private final AuthProperties authProperties;
    private final Clock clock;

    public AccountService(UserRepository users, AuthProperties authProperties, Clock clock) {
        this.users = users;
        this.authProperties = authProperties;
        this.clock = clock;
    }

    /** Called after every successful sign-in: creates the account on first visit, refreshes it afterwards. */
    @Transactional
    public UserAccount signIn(SignInProfile profile) {
        UserAccount account = users.findBySubject(profile.subject()).orElseGet(() -> register(profile));
        account.refreshProfile(profile);
        if (authProperties.isAdminEmail(profile.email()) && !account.isAdmin()) {
            account.promoteToAdmin(clock.instant());
        }
        return account;
    }

    @Transactional(readOnly = true)
    public Optional<UserAccount> findBySubject(String subject) {
        return users.findBySubject(subject);
    }

    @Transactional(readOnly = true)
    public List<UserAccount> list(AccountStatus status) {
        return status == null ? users.findAllByOrderByCreatedAtAsc() : users.findByStatusOrderByCreatedAtAsc(status);
    }

    @Transactional
    public UserAccount approve(UUID actingAdminId, UUID accountId) {
        UserAccount account = decidable(actingAdminId, accountId);
        account.approve(clock.instant());
        return account;
    }

    @Transactional
    public UserAccount reject(UUID actingAdminId, UUID accountId) {
        UserAccount account = decidable(actingAdminId, accountId);
        account.reject(clock.instant());
        return account;
    }

    private UserAccount register(SignInProfile profile) {
        boolean admin = authProperties.isAdminEmail(profile.email());
        return users.save(UserAccount.register(
                profile,
                admin ? AccountRole.ADMIN : AccountRole.USER,
                admin ? AccountStatus.APPROVED : AccountStatus.PENDING,
                clock.instant()));
    }

    /** Administrators cannot change their own status or each other's, so nobody can lock the system out. */
    private UserAccount decidable(UUID actingAdminId, UUID accountId) {
        UserAccount account = users.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
        if (account.getId().equals(actingAdminId)) {
            throw new InvalidAccountChangeException("You cannot change your own account");
        }
        if (account.isAdmin()) {
            throw new InvalidAccountChangeException("Administrator accounts cannot be approved or rejected");
        }
        return account;
    }
}
