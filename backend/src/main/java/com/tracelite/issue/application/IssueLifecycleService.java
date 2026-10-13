package com.tracelite.issue.application;

import com.tracelite.issue.domain.Issue;
import com.tracelite.issue.persistence.IssueRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IssueLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(IssueLifecycleService.class);

    private final IssueRepository issueRepository;
    private final Clock clock;

    public IssueLifecycleService(IssueRepository issueRepository, Clock clock) {
        this.issueRepository = issueRepository;
        this.clock = clock;
    }

    @Transactional
    public Issue resolve(UUID issueId) {
        Issue issue = findIssue(issueId);
        issue.resolve(clock.instant().truncatedTo(ChronoUnit.MICROS));
        Issue saved = issueRepository.saveAndFlush(issue);
        log.info("Resolved issue {}", issueId);
        return saved;
    }

    @Transactional
    public Issue reopen(UUID issueId) {
        Issue issue = findIssue(issueId);
        issue.reopen();
        Issue saved = issueRepository.saveAndFlush(issue);
        log.info("Reopened issue {}", issueId);
        return saved;
    }

    private Issue findIssue(UUID issueId) {
        return issueRepository.findById(issueId).orElseThrow(() -> new IssueNotFoundException(issueId));
    }
}
