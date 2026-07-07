package dev.forgeide.core.port;

/**
 * Adapter contract for {@code script} steps and deterministic judge rechecks (SD §6).
 * Implemented in {@code runtime}.
 */
public interface ScriptRunnerPort {
    ScriptResult run(ScriptInvocation invocation) throws ScriptRunnerException;
}
