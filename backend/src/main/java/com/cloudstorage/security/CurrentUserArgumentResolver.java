package com.cloudstorage.security;

import com.cloudstorage.account.AccountService;
import com.cloudstorage.account.UserAccount;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Loads the account behind the current session, fresh from the database, for {@link CurrentUser} parameters. */
@Component
class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    private final AccountService accounts;

    CurrentUserArgumentResolver(AccountService accounts) {
        this.accounts = accounts;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && UserAccount.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            throw new AuthenticationCredentialsNotFoundException("Not signed in");
        }
        return accounts.findBySubject(authentication.getName())
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Unknown account"));
    }
}
