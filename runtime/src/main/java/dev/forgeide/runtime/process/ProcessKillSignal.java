package dev.forgeide.runtime.process;

/**
 * Thrown by an {@code onStdoutLine}/{@code onStderrLine} consumer passed to {@link
 * ProcessRunner#run} to request an immediate kill of the whole process group — the same
 * mechanism the timeout and output-cap triggers use internally. Intended for callers that
 * detect a resource limit {@link ProcessRunner} itself has no notion of (e.g. a token budget
 * derived from parsed stream-json events); {@link ProcessRunner} stops draining that pipe and
 * kills the group as soon as this is caught, exactly like {@code overCap}.
 */
public final class ProcessKillSignal extends RuntimeException {

    public ProcessKillSignal(String message) {
        super(message);
    }
}
