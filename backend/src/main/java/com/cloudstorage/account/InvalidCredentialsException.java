package com.cloudstorage.account;

/** Wrong username or password. Deliberately does not say which one was wrong. */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Wrong username or password");
    }
}
