package com.cloudstorage.account;

import com.cloudstorage.TestDatabase;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;

@SpringBootTest(properties = {
        "storage.seed-demo-data=false",
        "auth.admin-usernames=boss,second-boss,seeded"
})
class AccountServiceTest {

    private static final String PASSWORD = "correct horse battery";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @Autowired
    AccountService accounts;

    @Autowired
    UserRepository users;

    private static String newName() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
    }

    private UserAccount person() {
        return accounts.register(newName(), PASSWORD);
    }

    private UserAccount admin(String name) {
        return accounts.findBySubject("user:" + name).orElseGet(() -> accounts.register(name, PASSWORD));
    }

    @Test
    void aNewPersonStartsPendingAndCannotUseTheDriveYet() {
        UserAccount account = person();

        assertThat(account.getStatus()).isEqualTo(AccountStatus.PENDING);
        assertThat(account.getRole()).isEqualTo(AccountRole.USER);
        assertThat(account.isApproved()).isFalse();
    }

    @Test
    void aConfiguredAdministratorUsernameIsApprovedImmediatelyEvenIfCasedDifferently() {
        UserAccount account = accounts.register("Second-Boss", PASSWORD);

        assertThat(account.getUsername()).isEqualTo("second-boss");
        assertThat(account.getRole()).isEqualTo(AccountRole.ADMIN);
        assertThat(account.isApproved()).isTrue();
    }

    @Test
    void thePasswordIsStoredOnlyAsAHashAndCheckedOnSignIn() {
        String name = newName();
        UserAccount created = accounts.register(name, PASSWORD);

        assertThat(created.getPasswordHash()).startsWith("$2").doesNotContain(PASSWORD);
        assertThat(accounts.authenticate(name.toUpperCase(), PASSWORD).getId()).isEqualTo(created.getId());
        assertThatThrownBy(() -> accounts.authenticate(name, "wrong password!"))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() -> accounts.authenticate("nobody-" + name, PASSWORD))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Wrong username or password");
    }

    @Test
    void aUsernameCanBeUsedOnlyOnce() {
        UserAccount first = person();

        assertThatThrownBy(() -> accounts.register(first.getUsername(), "another password"))
                .isInstanceOf(UsernameTakenException.class);
    }

    @Test
    void badUsernamesAndShortPasswordsAreRefused() {
        assertThatThrownBy(() -> accounts.register("ab", PASSWORD)).isInstanceOf(InvalidAccountChangeException.class);
        assertThatThrownBy(() -> accounts.register("has space", PASSWORD)).isInstanceOf(InvalidAccountChangeException.class);
        assertThatThrownBy(() -> accounts.register(newName(), "short")).isInstanceOf(InvalidAccountChangeException.class);
        assertThatThrownBy(() -> accounts.register(newName(), "x".repeat(73))).isInstanceOf(InvalidAccountChangeException.class);
    }

    @Test
    void theSeededAdministratorHasNoPasswordUntilSomeoneClaimsIt() {
        UserAccount seeded = accounts.ensureAdminAccount("seeded");
        assertThat(seeded.hasPassword()).isFalse();
        assertThatThrownBy(() -> accounts.authenticate("seeded", PASSWORD)).isInstanceOf(InvalidCredentialsException.class);

        UserAccount claimed = accounts.register("seeded", PASSWORD);

        assertThat(claimed.getId()).isEqualTo(seeded.getId());
        assertThat(claimed.hasPassword()).isTrue();
        assertThatThrownBy(() -> accounts.register("seeded", "someone else!!"))
                .isInstanceOf(UsernameTakenException.class);
    }

    @Test
    void anApprovedAccountWithoutAPasswordCannotBeTakenOverByRegistering() {
        UserAccount victim = person();
        UserAccount admin = admin("boss");
        accounts.approve(admin.getId(), victim.getId());
        victim = users.findById(victim.getId()).orElseThrow();
        victim.setPasswordHash(null);   // simulate a legacy account that was approved but never had a password
        users.saveAndFlush(victim);

        String name = victim.getUsername();
        assertThatThrownBy(() -> accounts.register(name, PASSWORD)).isInstanceOf(UsernameTakenException.class);
    }

    @Test
    void theDatabaseItselfRefusesTwoAccountsWithTheSameUsernameOrSubject() {
        String name = newName();
        users.saveAndFlush(UserAccount.create(name, name, "hash", AccountRole.USER, AccountStatus.PENDING, java.time.Instant.now()));

        assertThatThrownBy(() -> users.saveAndFlush(
                UserAccount.create(name, "dup", "hash", AccountRole.USER, AccountStatus.PENDING, java.time.Instant.now())))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("ux_accounts_");
    }

    @Test
    void anAdministratorCanApproveAndLaterRejectAPerson() {
        UserAccount admin = admin("boss");
        UserAccount person = person();

        assertThat(accounts.approve(admin.getId(), person.getId()).isApproved()).isTrue();
        assertThat(accounts.reject(admin.getId(), person.getId()).getStatus()).isEqualTo(AccountStatus.REJECTED);
        assertThat(accounts.list(AccountStatus.REJECTED)).extracting(UserAccount::getId).contains(person.getId());
    }

    @Test
    void administratorsCannotChangeThemselvesOrEachOther() {
        UserAccount admin = admin("boss");

        assertThatThrownBy(() -> accounts.reject(admin.getId(), admin.getId()))
                .isInstanceOf(InvalidAccountChangeException.class);

        UserAccount otherAdmin = admin("second-boss");
        assertThatThrownBy(() -> accounts.reject(admin.getId(), otherAdmin.getId()))
                .isInstanceOf(InvalidAccountChangeException.class);
    }
}
