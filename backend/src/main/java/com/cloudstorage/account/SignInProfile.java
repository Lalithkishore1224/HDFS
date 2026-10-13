package com.cloudstorage.account;

import java.util.Locale;

/** What an identity provider tells us about the person who just signed in. */
public record SignInProfile(String subject, String email, String name, String pictureUrl) {

    /** Profile for the development sign-in, whose "provider subject" is derived from the email. */
    public static SignInProfile forDevelopment(String email, String name) {
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        return new SignInProfile("dev:" + normalized, normalized, name, null);
    }
}
