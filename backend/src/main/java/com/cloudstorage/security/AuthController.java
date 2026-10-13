package com.cloudstorage.security;

import com.cloudstorage.account.AccountResponse;
import com.cloudstorage.account.UserAccount;
import com.cloudstorage.config.AuthProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tells the UI how people can sign in, and who is signed in. */
@RestController
@RequestMapping("/api")
class AuthController {

    /** @param googleEnabled whether "Continue with Google" is configured on this server */
    record AuthConfig(boolean googleEnabled, boolean devLoginEnabled) {
    }

    private final boolean googleEnabled;
    private final AuthProperties properties;

    AuthController(ObjectProvider<ClientRegistrationRepository> googleRegistrations, AuthProperties properties) {
        this.googleEnabled = googleRegistrations.getIfAvailable() != null;
        this.properties = properties;
    }

    @GetMapping("/auth/config")
    AuthConfig config() {
        return new AuthConfig(googleEnabled, properties.devLoginEnabled());
    }

    @GetMapping("/me")
    AccountResponse me(@CurrentUser UserAccount account) {
        return AccountResponse.from(account);
    }
}
