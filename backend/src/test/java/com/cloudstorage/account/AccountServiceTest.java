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
        "auth.admin-emails=boss@example.com,second-boss@example.com"
})
class AccountServiceTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @Autowired
    AccountService accounts;

    @Autowired
    UserRepository users;

    /** One person, one stable subject: the same email always maps to the same identity. */
    private SignInProfile profile(String email) {
        return new SignInProfile("google:" + email.toLowerCase(), email, "Someone", null);
    }

    @Test
    void aNewPersonStartsPendingAndCannotUseTheDriveYet() {
        UserAccount account = accounts.signIn(profile(UUID.randomUUID() + "@example.com"));

        assertThat(account.getStatus()).isEqualTo(AccountStatus.PENDING);
        assertThat(account.getRole()).isEqualTo(AccountRole.USER);
        assertThat(account.isApproved()).isFalse();
    }

    @Test
    void aConfiguredAdministratorEmailIsApprovedImmediatelyEvenIfCasedDifferently() {
        UserAccount account = accounts.signIn(profile("Boss@Example.com"));

        assertThat(account.getRole()).isEqualTo(AccountRole.ADMIN);
        assertThat(account.isApproved()).isTrue();
    }

    @Test
    void signingInAgainKeepsTheSameAccountAndRefreshesTheProfile() {
        SignInProfile first = new SignInProfile("google:same", "same@example.com", "Old Name", null);
        UserAccount created = accounts.signIn(first);
        UserAccount again = accounts.signIn(new SignInProfile("google:same", "same@example.com", "New Name", "http://pic"));

        assertThat(again.getId()).isEqualTo(created.getId());
        assertThat(again.getName()).isEqualTo("New Name");
        assertThat(again.getStatus()).isEqualTo(AccountStatus.PENDING);
    }

    @Test
    void theDatabaseItselfRefusesTwoAccountsForTheSameEmailOrSubject() {
        String email = UUID.randomUUID() + "@example.com";
        users.saveAndFlush(UserAccount.register(profile(email), AccountRole.USER, AccountStatus.PENDING, java.time.Instant.now()));

        SignInProfile sameEmailNewSubject = new SignInProfile("google:other-" + UUID.randomUUID(), email, "Dup", null);
        assertThatThrownBy(() -> users.saveAndFlush(
                UserAccount.register(sameEmailNewSubject, AccountRole.USER, AccountStatus.PENDING, java.time.Instant.now())))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("ux_accounts_email");

        SignInProfile sameSubjectNewEmail = new SignInProfile("google:" + email, UUID.randomUUID() + "@example.com", "Dup", null);
        assertThatThrownBy(() -> users.saveAndFlush(
                UserAccount.register(sameSubjectNewEmail, AccountRole.USER, AccountStatus.PENDING, java.time.Instant.now())))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("ux_accounts_subject");
    }

    @Test
    void anAdministratorCanApproveAndLaterRejectAPerson() {
        UserAccount admin = accounts.signIn(profile("boss@example.com"));
        UserAccount person = accounts.signIn(profile(UUID.randomUUID() + "@example.com"));

        assertThat(accounts.approve(admin.getId(), person.getId()).isApproved()).isTrue();
        assertThat(accounts.reject(admin.getId(), person.getId()).getStatus()).isEqualTo(AccountStatus.REJECTED);
        assertThat(accounts.list(AccountStatus.REJECTED)).extracting(UserAccount::getId).contains(person.getId());
    }

    @Test
    void administratorsCannotChangeThemselvesOrEachOther() {
        UserAccount admin = accounts.signIn(profile("boss@example.com"));

        assertThatThrownBy(() -> accounts.reject(admin.getId(), admin.getId()))
                .isInstanceOf(InvalidAccountChangeException.class);

        UserAccount otherAdmin = accounts.signIn(profile("second-boss@example.com"));
        assertThatThrownBy(() -> accounts.reject(admin.getId(), otherAdmin.getId()))
                .isInstanceOf(InvalidAccountChangeException.class);
    }
}
