package dev.forgeide.core.port;

import dev.forgeide.core.audit.AuditEvent;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.RunSnapshot;

import java.util.List;
import java.util.Optional;

/**
 * Source-of-truth persistence port (SD §4, SDD FR-7.1-7.3). Implemented in
 * {@code runtime}/{@code ui} against {@code ~/.forgeide/state/...}; {@code core} only
 * knows the contract.
 */
public interface StateStore {

    /** Persists synchronously; must complete before the corresponding event is published (FR-3.3). */
    void save(RunSnapshot snapshot);

    Optional<RunSnapshot> load(RunId runId);

    List<RunId> listRuns(String featureSlug);

    /** Returns the seq assigned by the store's chain tip (the caller's {@code event.seq()} is ignored). */
    long appendAudit(AuditEvent event);

    List<AuditEvent> loadAudit(RunId runId);
}
