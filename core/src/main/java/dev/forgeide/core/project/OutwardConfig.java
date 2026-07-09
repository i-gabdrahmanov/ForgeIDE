package dev.forgeide.core.project;

import java.util.Objects;
import java.util.Optional;

/**
 * IDE-held delivery config for a project's {@code outward} pipeline steps (SDD SR-4/T17):
 * which git remote/target branch {@code git_push}/{@code create_pr} deliver to, and the
 * PR/Jira endpoints they call. Deliberately not part of {@code pipeline.yaml} — see {@link
 * PrRepoConfig}'s javadoc — so the same reviewed pipeline template can run against different
 * hosts/branches per project without touching the versioned file.
 */
public record OutwardConfig(String gitRemote, String targetBranch, Optional<PrRepoConfig> pr,
                             Optional<JiraProjectConfig> jira) {

    /** No delivery target configured — {@code git_push} still works against {@code origin}/{@code
     * main}, but {@code create_pr}/{@code jira_*} refuse with a clear error rather than guessing. */
    public static final OutwardConfig EMPTY = new OutwardConfig("origin", "main", Optional.empty(), Optional.empty());

    public OutwardConfig {
        if (gitRemote == null || gitRemote.isBlank()) {
            throw new IllegalArgumentException("gitRemote must not be blank");
        }
        if (targetBranch == null || targetBranch.isBlank()) {
            throw new IllegalArgumentException("targetBranch must not be blank");
        }
        pr = Objects.requireNonNullElse(pr, Optional.empty());
        jira = Objects.requireNonNullElse(jira, Optional.empty());
    }
}
