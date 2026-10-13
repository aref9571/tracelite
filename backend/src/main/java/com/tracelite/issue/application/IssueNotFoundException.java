package com.tracelite.issue.application;

import java.util.UUID;

public class IssueNotFoundException extends RuntimeException {

    public IssueNotFoundException(UUID issueId) {
        super("Issue " + issueId + " does not exist");
    }
}
