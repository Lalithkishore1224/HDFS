package com.cloudstorage.security;

import com.cloudstorage.account.AccountResponse;
import com.cloudstorage.account.AccountService;
import com.cloudstorage.account.UserAccount;
import com.cloudstorage.config.AuthProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Create account, sign in, and who is signed in. */
@RestController
@RequestMapping("/api")
class AuthController {

    /** @param registrationOpen whether "Create account" is available on this server */
    record AuthConfig(boolean registrationOpen) {
    }

    record Credentials(@NotBlank String username, @NotBlank String password) {
    }

    private final AccountService accounts;
    private final AuthProperties properties;
    private final LoginThrottle throttle;
    private final SecurityContextRepository contextRepository;

    AuthController(AccountService accounts, AuthProperties properties, LoginThrottle throttle,
                   SecurityContextRepository contextRepository) {
        this.accounts = accounts;
        this.properties = properties;
        this.throttle = throttle;
        this.contextRepository = contextRepository;
    }

    @GetMapping("/auth/config")
    AuthConfig config() {
        return new AuthConfig(properties.registrationOpen());
    }

    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    AccountResponse register(@Valid @RequestBody Credentials credentials, HttpServletRequest request,
                             HttpServletResponse response) {
        UserAccount account = accounts.register(credentials.username(), credentials.password());
        startSession(account, request, response);
        return AccountResponse.from(account);
    }

    @PostMapping("/auth/login")
    AccountResponse login(@Valid @RequestBody Credentials credentials, HttpServletRequest request,
                          HttpServletResponse response) {
        String key = properties.normalized(credentials.username());
        throttle.checkAllowed(key);
        UserAccount account;
        try {
            account = accounts.authenticate(credentials.username(), credentials.password());
        } catch (RuntimeException e) {
            throttle.failed(key);
            throw e;
        }
        throttle.succeeded(key);
        startSession(account, request, response);
        return AccountResponse.from(account);
    }

    @GetMapping("/me")
    AccountResponse me(@CurrentUser UserAccount account) {
        return AccountResponse.from(account);
    }

    /** A fresh session id on every sign-in, so an id known before signing in is worthless afterwards. */
    private void startSession(UserAccount account, HttpServletRequest request, HttpServletResponse response) {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
                account.getSubject(), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContext context = SecurityContextHolder.getContextHolderStrategy().createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.getContextHolderStrategy().setContext(context);
        contextRepository.saveContext(context, request, response);
    }
}
