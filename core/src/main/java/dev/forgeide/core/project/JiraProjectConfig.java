package dev.forgeide.core.project;

/**
 * Where a project's {@code jira_comment}/{@code jira_transition} outward actions write (T17):
 * the Jira base URL and the workflow transition name {@code jira_transition} drives the issue
 * through. The issue key itself is a run-time value ({@code ${params.jira_key}}, SD §5), not
 * project config — the same project can carry a run for any ticket.
 */
public record JiraProjectConfig(String baseUrl, String transitionName) {

    public JiraProjectConfig {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        if (transitionName == null || transitionName.isBlank()) {
            throw new IllegalArgumentException("transitionName must not be blank");
        }
    }
}
