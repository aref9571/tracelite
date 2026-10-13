package com.tracelite.common.web;

public final class IdentifierRules {

    public static final String PATTERN = "[A-Za-z0-9._:-]+";
    public static final String MESSAGE = "must contain only letters, digits, '.', '_', ':' or '-'";

    private IdentifierRules() {
    }
}
