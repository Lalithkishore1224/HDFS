package com.cloudstorage.security;

import com.cloudstorage.account.AccountResponse;
import com.cloudstorage.account.AccountService;
import com.cloudstorage.account.SignInProfile;
import com.cloudstorage.account.UserAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Password-less sign-in so the approval workflow can be tried without Google credentials.
 * It exists only when {@code auth.dev-login-enabled=true} (the {@code dev} profile) and goes through
 * exactly the same account creation and approval rules as a real Google sign-in.
 */
@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty("auth.dev-login-enabled")
class DevLoginController {

    record DevLoginRequest(@NotBlank @Email String email, String name) {
    }

    private final AccountService accounts;
    private final SecurityContextRepository contextRepository;

    DevLoginController(AccountService accounts, SecurityContextRepository contextRepository) {
        this.accounts = accounts;
        this.contextRepository = contextRepository;
    }

    @PostMapping("/dev-login")
    AccountResponse signIn(@Valid @RequestBody DevLoginRequest request, HttpServletRequest httpRequest,
                           HttpServletResponse httpResponse) {
        String name = request.name() == null || request.name().isBlank() ? request.email() : request.name().strip();
        UserAccount account = accounts.signIn(SignInProfile.forDevelopment(request.email(), name));

        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
                account.getSubject(), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContext context = SecurityContextHolder.getContextHolderStrategy().createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.getContextHolderStrategy().setContext(context);
        contextRepository.saveContext(context, httpRequest, httpResponse);
        return AccountResponse.from(account);
    }
}
