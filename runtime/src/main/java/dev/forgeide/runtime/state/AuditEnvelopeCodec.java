package dev.forgeide.runtime.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.forgeide.core.audit.AuditEvent;
import dev.forgeide.core.run.RunId;

import java.time.Instant;

/** Maps {@link AuditEvent} onto the hash-chain envelope field names from SDD §5.3. */
final class AuditEnvelopeCodec {

    private final ObjectMapper mapper;

    AuditEnvelopeCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    ObjectNode toNode(AuditEvent event) {
        ObjectNode node = mapper.createObjectNode();
        node.put("seq", event.seq());
        node.put("ts", event.ts().toString());
        node.put("run", event.runId().value());
        node.put("step", event.stepId());
        node.put("iter", event.iteration());
        node.put("type", event.type());
        node.set("payload", event.payload());
        node.put("prev", event.prevHash());
        node.put("hash", event.hash());
        return node;
    }

    AuditEvent fromNode(JsonNode node) {
        long seq = node.get("seq").asLong();
        Instant ts = Instant.parse(node.get("ts").asText());
        RunId runId = new RunId(node.get("run").asText());
        JsonNode stepNode = node.get("step");
        String stepId = stepNode == null || stepNode.isNull() ? null : stepNode.asText();
        int iter = node.get("iter").asInt();
        String type = node.get("type").asText();
        JsonNode payload = node.get("payload");
        String prev = node.get("prev").asText();
        String hash = node.get("hash").asText();
        return new AuditEvent(seq, ts, runId, stepId, iter, type, payload, prev, hash);
    }
}
