package dev.forgeide.runtime.state;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * Deterministic JSON canonicalization for hash-chain and checksum inputs (SDD §5.3):
 * object keys sorted, no insignificant whitespace. Walks the parsed tree itself rather
 * than relying on a serializer's field order, so the same logical document always
 * hashes the same way regardless of how it was originally written.
 */
final class CanonicalJson {

    private CanonicalJson() {
    }

    static byte[] canonicalBytes(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        write(node, sb);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** {@code SHA-256(prefix + payload)}, hex-encoded (SDD SR-3: {@code hash = SHA-256(prev + canonical(payload))}). */
    static String sha256Hex(String prefix, byte[] payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(prefix.getBytes(StandardCharsets.UTF_8));
            digest.update(payload);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static void write(JsonNode node, StringBuilder sb) {
        if (node == null || node.isNull()) {
            sb.append("null");
        } else if (node.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            node.fields().forEachRemaining(e -> sorted.put(e.getKey(), e.getValue()));
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonNode> e : sorted.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(e.getKey(), sb);
                sb.append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
        } else if (node.isArray()) {
            sb.append('[');
            boolean first = true;
            for (JsonNode child : node) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(child, sb);
            }
            sb.append(']');
        } else if (node.isTextual()) {
            writeString(node.asText(), sb);
        } else if (node.isBoolean()) {
            sb.append(node.asBoolean());
        } else if (node.isNumber()) {
            sb.append(node.asText());
        } else {
            writeString(node.asText(), sb);
        }
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
