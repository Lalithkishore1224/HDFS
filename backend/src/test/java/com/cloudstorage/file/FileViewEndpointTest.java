package com.cloudstorage.file;

import com.cloudstorage.TestDatabase;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudstorage.account.AccountService;
import com.cloudstorage.account.SignInProfile;
import com.cloudstorage.account.UserAccount;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {
        "storage.seed-demo-data=false",
        "auth.admin-emails=viewer-admin@example.com"
})
@AutoConfigureMockMvc
class FileViewEndpointTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    AccountService accounts;

    @Autowired
    FileService files;

    private UserAccount approvedAdmin(String email) {
        return accounts.signIn(new SignInProfile("test:" + email, email, "Viewer", null));
    }

    private RequestPostProcessor as(UserAccount account) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(account.getSubject(), null, List.of()));
    }

    private StoredFile upload(UserAccount owner, String name, String body) {
        byte[] bytes = body.getBytes();
        return files.upload(owner, name, null, "application/x-claimed-by-uploader", bytes.length, new ByteArrayInputStream(bytes));
    }

    @Test
    void htmlIsServedAsInlinePlainTextSoItCanNeverRunInTheAppsOrigin() throws Exception {
        UserAccount owner = approvedAdmin("viewer-admin@example.com");
        StoredFile page = upload(owner, "evil.html", "<script>alert(1)</script>");

        mvc.perform(get("/api/files/" + page.getId() + "/view").with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/plain;charset=UTF-8"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("inline")))
                .andExpect(content().string("<script>alert(1)</script>"));
    }

    @Test
    void svgIsSandboxedByContentSecurityPolicy() throws Exception {
        UserAccount owner = approvedAdmin("viewer-admin@example.com");
        StoredFile image = upload(owner, "logo.svg", "<svg xmlns='http://www.w3.org/2000/svg'/>");

        mvc.perform(get("/api/files/" + image.getId() + "/view").with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/svg+xml"))
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.startsWith("sandbox")));
    }

    @Test
    void typesWithoutASafePreviewAreRefusedWithA415() throws Exception {
        UserAccount owner = approvedAdmin("viewer-admin@example.com");
        StoredFile archive = upload(owner, "bundle.zip", "PK");

        mvc.perform(get("/api/files/" + archive.getId() + "/view").with(as(owner)))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void anotherAccountCannotViewSomeoneElsesFile() throws Exception {
        UserAccount owner = approvedAdmin("viewer-admin@example.com");
        StoredFile secret = upload(owner, "private.txt", "secret");
        UserAccount stranger = accounts.signIn(
                new SignInProfile("test:stranger", "stranger@example.com", "Stranger", null));
        accounts.approve(owner.getId(), stranger.getId());

        mvc.perform(get("/api/files/" + secret.getId() + "/view").with(as(stranger)))
                .andExpect(status().isNotFound());
    }
}
