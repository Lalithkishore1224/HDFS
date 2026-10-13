package com.cloudstorage.security;

import com.cloudstorage.account.AccountService;
import com.cloudstorage.account.UserAccount;
import java.util.function.Supplier;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

/**
 * Who may call what. The account is looked up on every request rather than trusted from the session,
 * so an administrator's approval or rejection takes effect immediately for people already signed in.
 */
@Component
class AccessRules {

    private final AccountService accounts;

    AccessRules(AccountService accounts) {
        this.accounts = accounts;
    }

    /** Signed in and approved by an administrator. */
    AuthorizationManager<RequestAuthorizationContext> approvedMember() {
        return (authentication, context) -> new AuthorizationDecision(account(authentication).map(UserAccount::isApproved).orElse(false));
    }

    /** Signed in, approved and an administrator. */
    AuthorizationManager<RequestAuthorizationContext> administrator() {
        return (authentication, context) -> new AuthorizationDecision(
                account(authentication).map(a -> a.isApproved() && a.isAdmin()).orElse(false));
    }

    private java.util.Optional<UserAccount> account(Supplier<Authentication> authentication) {
        Authentication current = authentication.get();
        if (current == null || !current.isAuthenticated() || current.getName() == null) {
            return java.util.Optional.empty();
        }
        return accounts.findBySubject(current.getName());
    }
}
