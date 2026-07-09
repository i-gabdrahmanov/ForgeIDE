package dev.forgeide.runtime.outward;

import dev.forgeide.core.port.OutwardActionException;
import dev.forgeide.core.port.OutwardActionsPort;
import dev.forgeide.core.project.PrProvider;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Real {@link OutwardActionsPort} (T17/SR-4): {@link GitCliOutwardActions} for {@code git_push},
 * a {@link PullRequestClient} chosen by {@link PrProvider} for {@code create_pr}, and {@link
 * HttpJiraClient} for the two Jira actions. The one {@code core} -> real-world seam this whole
 * feature exists to isolate — {@code PipelineEngine} only ever sees the port interface.
 */
public final class DefaultOutwardActionsPort implements OutwardActionsPort {

    private final GitCliOutwardActions git;
    private final GitHubPullRequestClient github;
    private final BitbucketPullRequestClient bitbucket;
    private final HttpJiraClient jira;

    public DefaultOutwardActionsPort() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    public DefaultOutwardActionsPort(HttpClient http) {
        this.git = new GitCliOutwardActions();
        this.github = new GitHubPullRequestClient(http);
        this.bitbucket = new BitbucketPullRequestClient(http);
        this.jira = new HttpJiraClient(http);
    }

    @Override
    public Outcome gitPush(GitPushRequest request) throws OutwardActionException {
        return git.push(request);
    }

    @Override
    public Outcome createPr(CreatePrRequest request) throws OutwardActionException {
        PullRequestClient client = switch (request.repo().provider()) {
            case GITHUB -> github;
            case BITBUCKET -> bitbucket;
        };
        return client.createOrReuse(request);
    }

    @Override
    public Outcome jiraComment(JiraCommentRequest request) throws OutwardActionException {
        return jira.comment(request);
    }

    @Override
    public Outcome jiraTransition(JiraTransitionRequest request) throws OutwardActionException {
        return jira.transition(request);
    }
}
