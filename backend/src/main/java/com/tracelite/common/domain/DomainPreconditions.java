package com.tracelite.common.domain;

public final class DomainPreconditions {

    private DomainPreconditions() {
    }

    public static String requireText(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return limitLength(value, name, maxLength);
    }

    public static String limitLength(String value, String name, int maxLength) {
        if (value != null && value.length() > maxLength) {
            throw new IllegalArgumentException(name + " must be at most " + maxLength + " characters");
        }
        return value;
    }
}
