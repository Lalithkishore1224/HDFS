package com.cloudstorage.account;

/** Too many wrong passwords in a row for one username; try again shortly. */
public class TooManyAttemptsException extends RuntimeException {

    public TooManyAttemptsException(long retryAfterSeconds) {
        super("Too many failed attempts. Try again in " + retryAfterSeconds + " seconds");
    }
}
