package com.tracelite.issue.api;

import com.tracelite.common.web.PageResponse;
import com.tracelite.issue.application.IssueQueryService;
import com.tracelite.issue.domain.Issue;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/issues")
public class IssueController {

    private final IssueQueryService issueQueryService;

    public IssueController(IssueQueryService issueQueryService) {
        this.issueQueryService = issueQueryService;
    }

    @GetMapping
    public PageResponse<IssueResponse> list(@Valid @ParameterObject @ModelAttribute IssueSearchRequest request) {
        Page<Issue> issues = issueQueryService.search(
                request.toCriteria(),
                request.sortOrDefault(),
                request.directionOrDefault(),
                request.pageOrDefault(),
                request.sizeOrDefault());
        return PageResponse.from(issues, IssueResponse::from);
    }

    @GetMapping("/{id}")
    public IssueResponse get(@PathVariable UUID id) {
        return IssueResponse.from(issueQueryService.getIssue(id));
    }
}
