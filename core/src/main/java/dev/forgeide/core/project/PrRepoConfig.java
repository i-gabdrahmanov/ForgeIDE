package dev.forgeide.core.project;

import java.util.Objects;

/**
 * Where a project's {@code create_pr} outward action opens pull requests (T17): which hosted
 * provider, its API base URL, and the {@code owner/repo} (GitHub) or {@code workspace/repo}
 * (Bitbucket) slug. Lives in IDE-held project config, never in {@code pipeline.yaml} — the same
 * split as credentials (SR-5): a pipeline template is reused across projects/environments, the
 * concrete host it delivers to is not part of that template.
 */
public record PrRepoConfig(PrProvider provider, String apiBaseUrl, String repoSlug) {

    public PrRepoConfig {
        Objects.requireNonNull(provider, "provider");
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            throw new IllegalArgumentException("apiBaseUrl must not be blank");
        }
        if (repoSlug == null || repoSlug.isBlank()) {
            throw new IllegalArgumentException("repoSlug must not be blank");
        }
    }
}
