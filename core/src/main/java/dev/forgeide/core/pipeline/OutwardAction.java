package dev.forgeide.core.pipeline;

/**
 * Deterministic action executed by the engine itself, never by an agent phase (SR-4).
 */
public enum OutwardAction {
    GIT_PUSH,
    CREATE_PR,
    JIRA_COMMENT,
    JIRA_TRANSITION
}
