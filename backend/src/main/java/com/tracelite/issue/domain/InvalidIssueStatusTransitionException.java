package com.tracelite.issue.domain;

public class InvalidIssueStatusTransitionException extends RuntimeException {

    private final IssueStatus currentStatus;
    private final IssueStatus targetStatus;

    public InvalidIssueStatusTransitionException(IssueStatus currentStatus, IssueStatus targetStatus) {
        super("Cannot change issue status from " + currentStatus + " to " + targetStatus);
        this.currentStatus = currentStatus;
        this.targetStatus = targetStatus;
    }

    public IssueStatus getCurrentStatus() {
        return currentStatus;
    }

    public IssueStatus getTargetStatus() {
        return targetStatus;
    }
}
