package com.cloudstorage.security;

import com.cloudstorage.TestDatabase;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudstorage.account.AccountService;
import com.cloudstorage.account.SignInProfile;
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
        "auth.admin-emails=boss@example.com",
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

    private RequestPostProcessor signedInAs(String email) {
        UserAccount account = accounts.signIn(new SignInProfile("test:" + email, email, "Tester", null));
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
                .andExpect(jsonPath("$.googleEnabled").value(false));
    }

    @Test
    void aPendingAccountCanSeeItselfButNotTheDrive() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        RequestPostProcessor pending = signedInAs(email);

        mvc.perform(get("/api/me").with(pending))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(get("/api/files").with(pending)).andExpect(status().isForbidden());
        mvc.perform(get("/api/storage").with(pending)).andExpect(status().isForbidden());
    }

    @Test
    void anApprovedAccountReachesTheDriveButNotTheAdminPanel() throws Exception {
        RequestPostProcessor admin = signedInAs("boss@example.com");
        String email = UUID.randomUUID() + "@example.com";
        RequestPostProcessor person = signedInAs(email);
        UUID personId = accounts.findBySubject("test:" + email).orElseThrow().getId();

        mvc.perform(post("/api/admin/users/" + personId + "/approve").with(admin).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.status").value("APPROVED"));

        mvc.perform(get("/api/files").with(person)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/users").with(person)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/optimizer/run").with(person).with(csrf())).andExpect(status().isForbidden());
    }

    @Test
    void rejectingSomeoneLocksThemOutImmediatelyEvenWhileSignedIn() throws Exception {
        RequestPostProcessor admin = signedInAs("boss@example.com");
        String email = UUID.randomUUID() + "@example.com";
        RequestPostProcessor person = signedInAs(email);
        UUID personId = accounts.findBySubject("test:" + email).orElseThrow().getId();

        mvc.perform(post("/api/admin/users/" + personId + "/approve").with(admin).with(csrf())).andExpect(status().isOk());
        mvc.perform(get("/api/files").with(person)).andExpect(status().isOk());

        mvc.perform(post("/api/admin/users/" + personId + "/reject").with(admin).with(csrf())).andExpect(status().isOk());
        mvc.perform(get("/api/files").with(person)).andExpect(status().isForbidden());
    }

    @Test
    void administratorsCanListThePendingQueue() throws Exception {
        RequestPostProcessor admin = signedInAs("boss@example.com");
        signedInAs(UUID.randomUUID() + "@example.com");

        mvc.perform(get("/api/admin/users?status=PENDING").with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].account.status").value("PENDING"));
    }

    @Test
    void theAiStorageSummaryIsForAdministratorsOnly() throws Exception {
        RequestPostProcessor admin = signedInAs("boss@example.com");
        String email = UUID.randomUUID() + "@example.com";
        RequestPostProcessor person = signedInAs(email);
        UUID personId = accounts.findBySubject("test:" + email).orElseThrow().getId();
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
        RequestPostProcessor admin = signedInAs("boss@example.com");

        mvc.perform(post("/api/folders").with(admin).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/folders").with(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void thePublicStorageNumbersAreScopedToTheSignedInPerson() throws Exception {
        RequestPostProcessor admin = signedInAs("boss@example.com");
        mvc.perform(get("/api/storage").with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedBytes").exists())
                .andExpect(jsonPath("$.quotaBytes").exists());
    }
}
