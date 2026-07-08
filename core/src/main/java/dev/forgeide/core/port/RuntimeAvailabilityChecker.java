package dev.forgeide.core.port;

import dev.forgeide.core.project.RuntimeAvailability;
import dev.forgeide.core.project.RuntimeBinding;

/**
 * Probes whether a {@link RuntimeBinding} binary is usable (SDD FR-1.2: {@code --version}
 * check, status in UI). {@code core} may not spawn processes itself (see
 * {@code ArchitectureRulesTest#coreMustNotUseProcessBuilder}) — the real check is implemented
 * in {@code runtime} against {@link ProcessBuilder}.
 */
public interface RuntimeAvailabilityChecker {

    RuntimeAvailability check(RuntimeBinding runtime);
}
