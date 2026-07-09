package dev.forgeide.core.port;

import dev.forgeide.core.project.JiraProjectConfig;
import dev.forgeide.core.project.PrRepoConfig;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic execution of an {@code outward} step's actions (SDD SR-4/T17): {@code git push},
 * PR creation, Jira comment/transition. Called by {@code PipelineEngine} itself, from a worker
 * thread, never from inside an agent phase's process — the entire point of the {@code outward}
 * step type is that these effects are engine code, not something a model invoked.
 *
 * <p>Implemented against real git plumbing + the Bitbucket/GitHub/Jira REST APIs in {@code
 * runtime} ({@code DefaultOutwardActionsPort}); {@code core} only knows the contract, same split
 * as {@link ScopeDiffPort}/{@link ManifestProjectorPort}.
 */
public interface OutwardActionsPort {

    /** No-op implementation for engines/tests that don't exercise outward delivery — every
     * action "succeeds" with no result refs, same spirit as {@link ScopeDiffPort#NOOP}. */
    OutwardActionsPort NOOP = new OutwardActionsPort() {
        @Override
        public Outcome gitPush(GitPushRequest request) {
            return Outcome.EMPTY;
        }

        @Override
        public Outcome createPr(CreatePrRequest request) {
            return Outcome.EMPTY;
        }

        @Override
        public Outcome jiraComment(JiraCommentRequest request) {
            return Outcome.EMPTY;
        }

        @Override
        public Outcome jiraTransition(JiraTransitionRequest request) {
            return Outcome.EMPTY;
        }
    };

    /** Commits any uncommitted working-tree changes (a no-op if already clean — the retry-safe
     * path after a partial prior attempt) and pushes {@code HEAD} to {@code remote} as {@code
     * branch}. Pushing the same commit to the same branch twice is a no-op success, which is the
     * entire idempotency argument for a retried {@code git_push} (T17 acceptance). */
    Outcome gitPush(GitPushRequest request) throws OutwardActionException;

    /** Opens a PR from {@code sourceBranch} into {@code targetBranch}, or returns the existing
     * open one for that branch pair unchanged (T17 acceptance: "повторный запуск ... не создаёт
     * дубликат PR"). */
    Outcome createPr(CreatePrRequest request) throws OutwardActionException;

    /** Posts a comment, or does nothing if an identical one (by exact body) already exists on
     * the issue — the same retry-safety argument as {@link #createPr}. */
    Outcome jiraComment(JiraCommentRequest request) throws OutwardActionException;

    /** Drives the issue through {@link JiraProjectConfig#transitionName()}. If that transition is
     * not currently offered by Jira, the issue is treated as already past it (idempotent
     * success) rather than an error — the ordinary shape of a retried transition. */
    Outcome jiraTransition(JiraTransitionRequest request) throws OutwardActionException;

    record GitPushRequest(Path projectRoot, String remote, String branch, String commitMessage,
                           Map<String, String> env) {
        public GitPushRequest {
            Objects.requireNonNull(projectRoot, "projectRoot");
            requireNonBlank(remote, "remote");
            requireNonBlank(branch, "branch");
            requireNonBlank(commitMessage, "commitMessage");
            env = Map.copyOf(env);
        }
    }

    record CreatePrRequest(Path projectRoot, PrRepoConfig repo, String sourceBranch, String targetBranch,
                            String title, String body, Map<String, String> env) {
        public CreatePrRequest {
            Objects.requireNonNull(projectRoot, "projectRoot");
            Objects.requireNonNull(repo, "repo");
            requireNonBlank(sourceBranch, "sourceBranch");
            requireNonBlank(targetBranch, "targetBranch");
            requireNonBlank(title, "title");
            body = body == null ? "" : body;
            env = Map.copyOf(env);
        }
    }

    record JiraCommentRequest(JiraProjectConfig jira, String issueKey, String comment, Map<String, String> env) {
        public JiraCommentRequest {
            Objects.requireNonNull(jira, "jira");
            requireNonBlank(issueKey, "issueKey");
            requireNonBlank(comment, "comment");
            env = Map.copyOf(env);
        }
    }

    record JiraTransitionRequest(JiraProjectConfig jira, String issueKey, Map<String, String> env) {
        public JiraTransitionRequest {
            Objects.requireNonNull(jira, "jira");
            requireNonBlank(issueKey, "issueKey");
            env = Map.copyOf(env);
        }
    }

    /** External refs an action produced (e.g. {@code pr_url}, {@code pr_number}, {@code
     * comment_id}) — recorded verbatim into the audit trail and the step's artifacts panel (T17
     * scope note: "Результаты (URL PR, id комментария) → аудит + панель артефактов шага"). */
    record Outcome(Map<String, String> resultRefs) {
        public static final Outcome EMPTY = new Outcome(Map.of());

        public Outcome {
            resultRefs = Map.copyOf(resultRefs);
        }
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
