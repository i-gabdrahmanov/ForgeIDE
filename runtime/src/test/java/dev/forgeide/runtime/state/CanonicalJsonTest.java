package dev.forgeide.runtime.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void keyOrderDoesNotAffectCanonicalBytes() {
        ObjectNode a = mapper.createObjectNode();
        a.put("b", 2);
        a.put("a", 1);

        ObjectNode b = mapper.createObjectNode();
        b.put("a", 1);
        b.put("b", 2);

        assertThat(CanonicalJson.canonicalBytes(a)).isEqualTo(CanonicalJson.canonicalBytes(b));
    }

    @Test
    void differentContentProducesDifferentBytes() {
        ObjectNode a = mapper.createObjectNode().put("value", "x");
        ObjectNode b = mapper.createObjectNode().put("value", "y");

        assertThat(CanonicalJson.canonicalBytes(a)).isNotEqualTo(CanonicalJson.canonicalBytes(b));
    }

    @Test
    void sha256HexIsDeterministicAndPrefixSensitive() {
        byte[] payload = CanonicalJson.canonicalBytes(mapper.createObjectNode().put("n", 1));

        String h1 = CanonicalJson.sha256Hex("prevA", payload);
        String h2 = CanonicalJson.sha256Hex("prevA", payload);
        String h3 = CanonicalJson.sha256Hex("prevB", payload);

        assertThat(h1).isEqualTo(h2);
        assertThat(h1).isNotEqualTo(h3);
        assertThat(h1).hasSize(64); // hex-encoded SHA-256
    }
}
