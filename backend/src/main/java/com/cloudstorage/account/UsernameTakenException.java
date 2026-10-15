package com.cloudstorage.account;

/** Someone else already has that username. */
public class UsernameTakenException extends RuntimeException {

    public UsernameTakenException(String username) {
        super("The username \"" + username + "\" is already taken");
    }
}
