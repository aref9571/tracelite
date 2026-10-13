package com.tracelite.issue.application;

public enum IssueSortField {
    LAST_SEEN("lastSeenAt"),
    FIRST_SEEN("firstSeenAt"),
    OCCURRENCE_COUNT("occurrenceCount");

    private final String property;

    IssueSortField(String property) {
        this.property = property;
    }

    public String getProperty() {
        return property;
    }
}
