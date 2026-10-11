package com.tracelite.issue.domain;

public enum IssueStatus {
    UNRESOLVED,
    RESOLVED;

    public boolean canTransitionTo(IssueStatus target) {
        return this != target;
    }
}
