package dev.forgeide.core.engine.support;

import dev.forgeide.core.port.OutwardActionException;
import dev.forgeide.core.port.OutwardActionsPort;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Scripted {@link OutwardActionsPort} fixture: each action is independently overridable, and
 * every call (request + which attempt number) is recorded for the test to inspect. */
public final class FixtureOutwardActionsPort implements OutwardActionsPort {

    @FunctionalInterface
    public interface Action<R> {
        Outcome handle(R request, int attempt) throws OutwardActionException;
    }

    private final List<Object> calls = new ArrayList<>();
    private final AtomicInteger gitPushAttempts = new AtomicInteger();
    private final AtomicInteger createPrAttempts = new AtomicInteger();

    private Action<GitPushRequest> gitPush = (request, attempt) -> Outcome.EMPTY;
    private Action<CreatePrRequest> createPr = (request, attempt) -> new Outcome(Map.of("pr_url", "https://example/pr/1"));
    private Action<JiraCommentRequest> jiraComment = (request, attempt) -> Outcome.EMPTY;
    private Action<JiraTransitionRequest> jiraTransition = (request, attempt) -> Outcome.EMPTY;

    public FixtureOutwardActionsPort onGitPush(Action<GitPushRequest> action) {
        this.gitPush = action;
        return this;
    }

    public FixtureOutwardActionsPort onCreatePr(Action<CreatePrRequest> action) {
        this.createPr = action;
        return this;
    }

    public FixtureOutwardActionsPort onJiraComment(Action<JiraCommentRequest> action) {
        this.jiraComment = action;
        return this;
    }

    public FixtureOutwardActionsPort onJiraTransition(Action<JiraTransitionRequest> action) {
        this.jiraTransition = action;
        return this;
    }

    public synchronized List<Object> calls() {
        return List.copyOf(calls);
    }

    @Override
    public synchronized Outcome gitPush(GitPushRequest request) throws OutwardActionException {
        calls.add(request);
        return gitPush.handle(request, gitPushAttempts.incrementAndGet());
    }

    @Override
    public synchronized Outcome createPr(CreatePrRequest request) throws OutwardActionException {
        calls.add(request);
        return createPr.handle(request, createPrAttempts.incrementAndGet());
    }

    @Override
    public synchronized Outcome jiraComment(JiraCommentRequest request) throws OutwardActionException {
        calls.add(request);
        return jiraComment.handle(request, 1);
    }

    @Override
    public synchronized Outcome jiraTransition(JiraTransitionRequest request) throws OutwardActionException {
        calls.add(request);
        return jiraTransition.handle(request, 1);
    }
}
