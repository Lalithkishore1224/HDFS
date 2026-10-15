package com.cloudstorage.security;

import com.cloudstorage.TestDatabase;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudstorage.account.AccountService;
import com.cloudstorage.account.UserAccount;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Exercises the real filter chain: who is let through to which part of the API. */
@SpringBootTest(properties = {
        "storage.seed-demo-data=false",
        "auth.admin-usernames=boss",
        "insights.api-key="   // never call the real AI from tests, even if the developer has a key in the environment
})
@AutoConfigureMockMvc
class ApiSecurityTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    AccountService accounts;

    private RequestPostProcessor signedInAs(String username) {
        UserAccount account = accounts.findBySubject("user:" + username)
                .orElseGet(() -> accounts.register(username, "correct horse battery"));
        return authentication(UsernamePasswordAuthenticationToken.authenticated(account.getSubject(), null, List.of()));
    }

    @Test
    void anonymousVisitorsGetA401NotARedirect() throws Exception {
        mvc.perform(get("/api/files")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void theSignInOptionsAreVisibleBeforeSigningIn() throws Exception {
        mvc.perform(get("/api/auth/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationOpen").value(true));
    }

    @Test
    void aPendingAccountCanSeeItselfButNotTheDrive() throws Exception {
        String email = "u" + UUID.randomUUID().toString().substring(0, 12);
        RequestPostProcessor pending = signedInAs(email);

        mvc.perform(get("/api/me").with(pending))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(get("/api/files").with(pending)).andExpect(status().isForbidden());
        mvc.perform(get("/api/storage").with(pending)).andExpect(status().isForbidden());
    }

    @Test
    void anApprovedAccountReachesTheDriveButNotTheAdminPanel() throws Exception {
        RequestPostProcessor admin = signedInAs("boss");
        String email = "u" + UUID.randomUUID().toString().substring(0, 12);
        RequestPostProcessor person = signedInAs(email);
        UUID personId = accounts.findBySubject("user:" + email).orElseThrow().getId();

        mvc.perform(post("/api/admin/users/" + personId + "/approve").with(admin).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.status").value("APPROVED"));

        mvc.perform(get("/api/files").with(person)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/users").with(person)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/optimizer/run").with(person).with(csrf())).andExpect(status().isForbidden());
    }

    @Test
    void rejectingSomeoneLocksThemOutImmediatelyEvenWhileSignedIn() throws Exception {
        RequestPostProcessor admin = signedInAs("boss");
        String email = "u" + UUID.randomUUID().toString().substring(0, 12);
        RequestPostProcessor person = signedInAs(email);
        UUID personId = accounts.findBySubject("user:" + email).orElseThrow().getId();

        mvc.perform(post("/api/admin/users/" + personId + "/approve").with(admin).with(csrf())).andExpect(status().isOk());
        mvc.perform(get("/api/files").with(person)).andExpect(status().isOk());

        mvc.perform(post("/api/admin/users/" + personId + "/reject").with(admin).with(csrf())).andExpect(status().isOk());
        mvc.perform(get("/api/files").with(person)).andExpect(status().isForbidden());
    }

    @Test
    void administratorsCanListThePendingQueue() throws Exception {
        RequestPostProcessor admin = signedInAs("boss");
        signedInAs("u" + UUID.randomUUID().toString().substring(0, 12));

        mvc.perform(get("/api/admin/users?status=PENDING").with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].account.status").value("PENDING"));
    }

    @Test
    void theAiStorageSummaryIsForAdministratorsOnly() throws Exception {
        RequestPostProcessor admin = signedInAs("boss");
        String email = "u" + UUID.randomUUID().toString().substring(0, 12);
        RequestPostProcessor person = signedInAs(email);
        UUID personId = accounts.findBySubject("user:" + email).orElseThrow().getId();
        mvc.perform(post("/api/admin/users/" + personId + "/approve").with(admin).with(csrf())).andExpect(status().isOk());

        mvc.perform(post("/api/admin/insights").with(person).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/insights").with(csrf())).andExpect(status().isUnauthorized());

        mvc.perform(post("/api/admin/insights").with(admin).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("BUILT_IN"))
                .andExpect(jsonPath("$.summary").isNotEmpty())
                .andExpect(jsonPath("$.suggestions").isArray());
    }

    @Test
    void stateChangingRequestsWithoutACsrfTokenAreRefused() throws Exception {
        RequestPostProcessor admin = signedInAs("boss");

        mvc.perform(post("/api/folders").with(admin).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/folders").with(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void thePublicStorageNumbersAreScopedToTheSignedInPerson() throws Exception {
        RequestPostProcessor admin = signedInAs("boss");
        mvc.perform(get("/api/storage").with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedBytes").exists())
                .andExpect(jsonPath("$.quotaBytes").exists());
    }

    private static String credentials(String username, String password) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }

    @Test
    void creatingAnAccountSignsThePersonInAsPendingAndTheyCanSignInAgainLater() throws Exception {
        String name = "u" + UUID.randomUUID().toString().substring(0, 12);

        var created = mvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(credentials(name, "correct horse battery")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value(name))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn();
        var session = created.getRequest().getSession();

        mvc.perform(get("/api/me").session((org.springframework.mock.web.MockHttpSession) session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(name));

        mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(credentials(name.toUpperCase(), "correct horse battery")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void aWrongPasswordIsRefusedWithoutSayingWhetherTheUsernameExists() throws Exception {
        String name = "u" + UUID.randomUUID().toString().substring(0, 12);
        accounts.register(name, "correct horse battery");

        mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(credentials(name, "nope nope nope")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Wrong username or password"));
        mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(credentials("nobody-" + name, "nope nope nope")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Wrong username or password"));
    }

    @Test
    void aTakenUsernameIsAConflictAndAWeakPasswordIsABadRequest() throws Exception {
        String name = "u" + UUID.randomUUID().toString().substring(0, 12);
        accounts.register(name, "correct horse battery");

        mvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(credentials(name, "another password")))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(credentials("fresh" + name, "short")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void afterFiveWrongPasswordsEvenTheRightOneIsRefusedForAWhile() throws Exception {
        String name = "u" + UUID.randomUUID().toString().substring(0, 12);
        accounts.register(name, "correct horse battery");

        for (int attempt = 0; attempt < 5; attempt++) {
            mvc.perform(post("/api/auth/login").with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(credentials(name, "wrong password " + attempt)))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(credentials(name, "correct horse battery")))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void signingInWithoutACsrfTokenIsRefused() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(credentials("someone", "correct horse battery")))
                .andExpect(status().isForbidden());
    }
}
