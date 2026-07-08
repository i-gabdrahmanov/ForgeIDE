package dev.forgeide.core.engine.support;

import dev.forgeide.core.audit.AuditEvent;
import dev.forgeide.core.port.StateStore;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.RunSnapshot;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Trivial in-memory {@link StateStore} fixture for engine tests. {@link #history()} keeps every
 * snapshot ever saved so a test can assert on the granular sequence of transitions, not just the
 * latest one; {@link #audit()} does the same for appended audit events (T10 adds real audit
 * emission to {@code PipelineEngine} — this fixture now actually records it instead of no-oping).
 */
public final class InMemoryStateStore implements StateStore {

    private final Map<RunId, RunSnapshot> latest = new ConcurrentHashMap<>();
    private final List<RunSnapshot> history = new CopyOnWriteArrayList<>();
    private final List<AuditEvent> audit = new CopyOnWriteArrayList<>();
    private final Map<RunId, AtomicLong> auditSeqs = new ConcurrentHashMap<>();

    @Override
    public void save(RunSnapshot snapshot) {
        latest.put(snapshot.runId(), snapshot);
        history.add(snapshot);
    }

    @Override
    public Optional<RunSnapshot> load(RunId runId) {
        return Optional.ofNullable(latest.get(runId));
    }

    @Override
    public List<RunId> listRuns(String featureSlug) {
        return history.stream()
                .filter(s -> s.featureSlug().equals(featureSlug))
                .map(RunSnapshot::runId)
                .distinct()
                .toList();
    }

    @Override
    public long appendAudit(AuditEvent event) {
        long seq = auditSeqs.computeIfAbsent(event.runId(), id -> new AtomicLong()).incrementAndGet();
        audit.add(new AuditEvent(seq, event.ts(), event.runId(), event.stepId(), event.iteration(),
                event.type(), event.payload(), event.prevHash(), event.hash()));
        return seq;
    }

    @Override
    public List<AuditEvent> loadAudit(RunId runId) {
        return audit.stream().filter(e -> e.runId().equals(runId)).toList();
    }

    public List<RunSnapshot> history() {
        return List.copyOf(history);
    }

    /** Every audit event appended across every run, in append order. */
    public List<AuditEvent> audit() {
        return List.copyOf(audit);
    }
}
