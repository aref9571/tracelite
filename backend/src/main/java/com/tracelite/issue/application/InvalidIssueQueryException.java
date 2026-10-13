package com.tracelite.issue.application;

public class InvalidIssueQueryException extends RuntimeException {

    private final String field;
    private final String reason;

    public InvalidIssueQueryException(String field, String reason) {
        super(field + " " + reason);
        this.field = field;
        this.reason = reason;
    }

    public String getField() {
        return field;
    }

    public String getReason() {
        return reason;
    }
}
