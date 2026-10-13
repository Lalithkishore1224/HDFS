package com.cloudstorage.account;

public class InvalidAccountChangeException extends RuntimeException {

    public InvalidAccountChangeException(String message) {
        super(message);
    }
}
