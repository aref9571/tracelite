package com.tracelite.security;

public record IngestionClient(String application) {

    public boolean mayIngestFor(String requestedApplication) {
        return application.equals(requestedApplication);
    }
}
