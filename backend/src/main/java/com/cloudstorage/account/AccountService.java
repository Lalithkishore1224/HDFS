package com.cloudstorage.account;

import com.cloudstorage.config.AuthProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account creation, sign-in and approval.
 * <p>
 * "Create account" makes a PENDING account, unless the username is configured as an administrator.
 * Pending accounts can sign in but cannot use the drive until an administrator approves them.
 * Passwords are stored only as BCrypt hashes.
 */
@Service
public class AccountService {

    public static final Pattern USERNAME = Pattern.compile("[a-z0-9][a-z0-9._-]{2,29}");
    public static final int MIN_PASSWORD_LENGTH = 8;
    /** BCrypt only looks at the first 72 bytes, so longer passwords are refused instead of silently cut. */
    private static final int MAX_PASSWORD_BYTES = 72;

    private final UserRepository users;
    private final AuthProperties authProperties;
    private final Clock clock;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    /** Checked when the username does not exist, so a wrong username takes as long as a wrong password. */
    private final String unknownUserHash = encoder.encode("not-a-real-password");

    public AccountService(UserRepository users, AuthProperties authProperties, Clock clock) {
        this.users = users;
        this.authProperties = authProperties;
        this.clock = clock;
    }

    /**
     * Creates an account, or, for an administrator username or a pending legacy account that has never had a
     * password, sets the password on the existing account (so the seeded administrator can be taken over by
     * whoever registers that name first).
     */
    @Transactional
    public UserAccount register(String rawUsername, String rawPassword) {
        if (!authProperties.registrationOpen()) {
            throw new InvalidAccountChangeException("Creating accounts is switched off on this server");
        }
        String username = authProperties.normalized(rawUsername == null ? "" : rawUsername);
        if (!USERNAME.matcher(username).matches()) {
            throw new InvalidAccountChangeException(
                    "A username is 3 to 30 characters: letters, digits, dot, dash or underscore");
        }
        requireUsablePassword(rawPassword);
        boolean admin = authProperties.isAdminUsername(username);

        Optional<UserAccount> existing = users.findByUsername(username);
        if (existing.isPresent()) {
            UserAccount account = existing.get();
            boolean claimable = !account.hasPassword() && (admin || account.getStatus() == AccountStatus.PENDING);
            if (!claimable) {
                throw new UsernameTakenException(username);
            }
            account.setPasswordHash(encoder.encode(rawPassword));
            if (admin && !account.isAdmin()) {
                account.promoteToAdmin(clock.instant());
            }
            return account;
        }
        try {
            return users.saveAndFlush(UserAccount.create(
                    username, username, encoder.encode(rawPassword),
                    admin ? AccountRole.ADMIN : AccountRole.USER,
                    admin ? AccountStatus.APPROVED : AccountStatus.PENDING,
                    clock.instant()));
        } catch (DataIntegrityViolationException e) {
            throw new UsernameTakenException(username);   // two people registered the same name at once
        }
    }

    /** Checks the password. The same exception for "no such user", "no password set" and "wrong password". */
    @Transactional(readOnly = true)
    public UserAccount authenticate(String rawUsername, String rawPassword) {
        String username = rawUsername == null ? "" : authProperties.normalized(rawUsername);
        Optional<UserAccount> found = users.findByUsername(username);
        String hash = found.filter(UserAccount::hasPassword).map(UserAccount::getPasswordHash).orElse(unknownUserHash);
        boolean matches = rawPassword != null && encoder.matches(rawPassword, hash);
        if (found.isEmpty() || !found.get().hasPassword() || !matches) {
            throw new InvalidCredentialsException();
        }
        return found.get();
    }

    /** Creates the sample-data owner without a password; whoever registers that username sets it. */
    @Transactional
    public UserAccount ensureAdminAccount(String rawUsername) {
        String username = authProperties.normalized(rawUsername);
        return users.findByUsername(username).orElseGet(() -> users.save(UserAccount.create(
                username, username, null, AccountRole.ADMIN, AccountStatus.APPROVED, clock.instant())));
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

    private static void requireUsablePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new InvalidAccountChangeException("The password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new InvalidAccountChangeException("The password is too long (at most 72 bytes)");
        }
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
