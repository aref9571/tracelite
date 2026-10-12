package com.tracelite.event.application;

public class InvalidErrorEventException extends RuntimeException {

    private final String field;
    private final String reason;

    public InvalidErrorEventException(String field, String reason) {
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
