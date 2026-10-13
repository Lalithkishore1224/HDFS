package com.cloudstorage.config;

import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Sign-in and approval settings.
 *
 * @param adminEmails     accounts with these emails become administrators, approved on first sign-in
 * @param devLoginEnabled turns on a password-less sign-in for local development; never enable in production
 */
@ConfigurationProperties("auth")
public record AuthProperties(List<String> adminEmails, boolean devLoginEnabled) {

    public AuthProperties {
        adminEmails = adminEmails == null ? List.of() : List.copyOf(adminEmails);
    }

    public boolean isAdminEmail(String email) {
        return email != null && adminEmails.stream().anyMatch(admin -> admin.strip().equalsIgnoreCase(email.strip()));
    }

    public String normalized(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
