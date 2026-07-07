package dev.forgeide.core.audit;

import com.fasterxml.jackson.databind.JsonNode;
import dev.forgeide.core.run.RunId;

import java.time.Instant;
import java.util.Objects;

/**
 * Hash-chain audit envelope (SDD §5.3). Hash-chain computation and persistence
 * belong to the {@code StateStore} implementation (T07); this is only the shape.
 */
public record AuditEvent(
        long seq,
        Instant ts,
        RunId runId,
        String stepId,
        int iteration,
        String type,
        JsonNode payload,
        String prevHash,
        String hash
) {

    public AuditEvent {
        Objects.requireNonNull(ts, "ts");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(prevHash, "prevHash");
        Objects.requireNonNull(hash, "hash");
    }
}
