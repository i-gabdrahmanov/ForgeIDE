package dev.forgeide.runtime.process;

import java.time.Duration;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Raw result of a {@link ProcessRunner#run} call. Deliberately has no notion of step
 * success/failure — exit code 0 vs. non-zero, presence of a {@code result} event, artifact
 * validation etc. are all decided by the caller (T09), not here (SD §6.1 item 4).
 *
 * @param exitCode          POSIX exit status; a killed process still yields one (signal deaths
 *                          are reported as 128+signal by the JVM)
 * @param timedOut          {@code true} if the wall-clock cap ({@link ProcessSpec#timeout()})
 *                          was hit and the process group was killed as a result
 * @param outputCapExceeded {@code true} if {@link ProcessSpec#maxOutputBytes()} was hit and the
 *                          process group was killed as a result
 * @param wallClock         time from launch to the child's exit being reaped
 * @param pgid              the process group id the child was launched into, if it could be
 *                          determined (SR-9); empty only if the child exited before the group
 *                          could be discovered
 */
public record ProcessOutcome(int exitCode, boolean timedOut, boolean outputCapExceeded,
                              Duration wallClock, OptionalLong pgid) {

    public ProcessOutcome {
        Objects.requireNonNull(wallClock, "wallClock");
        Objects.requireNonNull(pgid, "pgid");
    }
}
