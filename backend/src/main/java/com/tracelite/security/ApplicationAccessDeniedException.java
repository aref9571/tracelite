package com.tracelite.security;

public class ApplicationAccessDeniedException extends RuntimeException {

    public ApplicationAccessDeniedException() {
        super("The API key does not belong to the requested application");
    }
}
