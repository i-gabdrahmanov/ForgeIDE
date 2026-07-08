package dev.forgeide.runtime.process;

/**
 * Thrown when a {@link ProcessRunner} invocation cannot even be started, e.g. the process
 * group could not be discovered before the child already exited, or the platform is not
 * supported (NFR-5: macOS/Linux only, Windows is post-MVP).
 */
public final class ProcessLaunchException extends RuntimeException {

    public ProcessLaunchException(String message) {
        super(message);
    }

    public ProcessLaunchException(String message, Throwable cause) {
        super(message, cause);
    }
}
