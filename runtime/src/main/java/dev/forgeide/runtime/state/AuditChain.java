package dev.forgeide.runtime.state;

import dev.forgeide.core.audit.AuditEvent;

import java.util.List;

/**
 * Pure hash-chain verifier (SD §4, SDD SR-3/FR-7.3): replays {@code seq}/{@code prev} and
 * recomputes {@code hash = SHA-256(prev + canonical(payload))} for every event in order,
 * comparing against the stored envelope. Any single-record tamper (payload edit, deleted
 * record, reordered record) desyncs this replay from the first affected record onward.
 */
final class AuditChain {

    static final String GENESIS_HASH = "";

    private AuditChain() {
    }

    static boolean verify(List<AuditEvent> events) {
        String expectedPrev = GENESIS_HASH;
        long expectedSeq = 1;
        for (AuditEvent event : events) {
            if (event.seq() != expectedSeq) {
                return false;
            }
            if (!event.prevHash().equals(expectedPrev)) {
                return false;
            }
            byte[] payloadBytes = CanonicalJson.canonicalBytes(event.payload());
            String recomputed = CanonicalJson.sha256Hex(expectedPrev, payloadBytes);
            if (!recomputed.equals(event.hash())) {
                return false;
            }
            expectedPrev = event.hash();
            expectedSeq++;
        }
        return true;
    }
}
