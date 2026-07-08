package dev.forgeide.runtime.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.forgeide.core.audit.AuditEvent;
import dev.forgeide.core.run.RunId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuditChainTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RunId runId = RunId.newId();

    @Test
    void validChainVerifies() {
        assertThat(AuditChain.verify(chain(3))).isTrue();
    }

    @Test
    void tamperedPayloadBreaksVerification() {
        List<AuditEvent> events = new ArrayList<>(chain(3));
        AuditEvent tampered = events.get(1);
        ObjectNode corruptPayload = mapper.createObjectNode().put("note", "tampered");
        events.set(1, new AuditEvent(tampered.seq(), tampered.ts(), tampered.runId(), tampered.stepId(),
                tampered.iteration(), tampered.type(), corruptPayload, tampered.prevHash(), tampered.hash()));

        assertThat(AuditChain.verify(events)).isFalse();
    }

    @Test
    void deletedRecordBreaksVerification() {
        List<AuditEvent> events = new ArrayList<>(chain(3));
        events.remove(1);

        assertThat(AuditChain.verify(events)).isFalse();
    }

    @Test
    void wrongPrevHashBreaksVerification() {
        List<AuditEvent> events = new ArrayList<>(chain(3));
        AuditEvent e = events.get(2);
        events.set(2, new AuditEvent(e.seq(), e.ts(), e.runId(), e.stepId(), e.iteration(), e.type(),
                e.payload(), "not-the-real-prev", e.hash()));

        assertThat(AuditChain.verify(events)).isFalse();
    }

    @Test
    void emptyChainVerifiesTrivially() {
        assertThat(AuditChain.verify(List.of())).isTrue();
    }

    /** Builds a genuinely valid hash chain of {@code n} events, mirroring what {@link FileStateStore} computes. */
    private List<AuditEvent> chain(int n) {
        List<AuditEvent> events = new ArrayList<>();
        String prev = AuditChain.GENESIS_HASH;
        for (int i = 1; i <= n; i++) {
            ObjectNode payload = mapper.createObjectNode().put("note", "event-" + i);
            byte[] payloadBytes = CanonicalJson.canonicalBytes(payload);
            String hash = CanonicalJson.sha256Hex(prev, payloadBytes);
            events.add(new AuditEvent(i, Instant.parse("2026-07-07T12:00:00Z"), runId, "step" + i, 1,
                    "step.passed", payload, prev, hash));
            prev = hash;
        }
        return events;
    }
}
