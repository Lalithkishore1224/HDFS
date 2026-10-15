package com.cloudstorage.config;

import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Sign-in and approval settings.
 *
 * @param adminUsernames   accounts created with one of these usernames become administrators, approved at once
 * @param registrationOpen whether "Create account" is available
 */
@ConfigurationProperties("auth")
public record AuthProperties(List<String> adminUsernames, Boolean registrationOpen) {

    public AuthProperties {
        adminUsernames = adminUsernames == null ? List.of() : List.copyOf(adminUsernames);
        registrationOpen = registrationOpen == null || registrationOpen;
    }

    public boolean isAdminUsername(String username) {
        return username != null && adminUsernames.stream().anyMatch(admin -> admin.strip().equalsIgnoreCase(username.strip()));
    }

    public String normalized(String username) {
        return username.strip().toLowerCase(Locale.ROOT);
    }
}
