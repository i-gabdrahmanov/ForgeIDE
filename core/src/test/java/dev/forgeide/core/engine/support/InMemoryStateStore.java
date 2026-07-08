package dev.forgeide.core.engine.support;

import dev.forgeide.core.audit.AuditEvent;
import dev.forgeide.core.port.StateStore;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.RunSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Trivial in-memory {@link StateStore} fixture for engine tests (T06 does not exercise real
 * persistence — that is T07). {@link #history()} keeps every snapshot ever saved so a test can
 * assert on the granular sequence of transitions, not just the latest one.
 */
public final class InMemoryStateStore implements StateStore {

    private final Map<RunId, RunSnapshot> latest = new ConcurrentHashMap<>();
    private final List<RunSnapshot> history = new CopyOnWriteArrayList<>();

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
    public void appendAudit(AuditEvent event) {
        // Audit hash-chain persistence is T07 scope; unused by the T06 engine.
    }

    @Override
    public List<AuditEvent> loadAudit(RunId runId) {
        return new ArrayList<>();
    }

    public List<RunSnapshot> history() {
        return List.copyOf(history);
    }
}
