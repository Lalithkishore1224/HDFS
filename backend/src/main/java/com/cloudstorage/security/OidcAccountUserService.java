package com.cloudstorage.security;

import com.cloudstorage.account.AccountService;
import com.cloudstorage.account.SignInProfile;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/** Runs after Google has authenticated the person: records them in our own account table. */
@Component
class OidcAccountUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private final OidcUserService googleUserService = new OidcUserService();
    private final AccountService accounts;

    OidcAccountUserService(AccountService accounts) {
        this.accounts = accounts;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
        OidcUser googleUser = googleUserService.loadUser(request);
        if (googleUser.getEmail() == null || !Boolean.TRUE.equals(googleUser.getEmailVerified())) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("email_not_verified"), "Your Google account has no verified email address");
        }
        accounts.signIn(new SignInProfile(
                googleUser.getSubject(), googleUser.getEmail(), googleUser.getFullName(), googleUser.getPicture()));
        return googleUser;
    }
}
