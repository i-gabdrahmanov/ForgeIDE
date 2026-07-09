package dev.forgeide.ui.run;

import dev.forgeide.core.audit.AuditEvent;

import java.util.Arrays;
import java.util.List;

/**
 * Parses the violating paths back out of an {@code incident.scope} audit event's {@code detail}
 * (SDD SR-6/Т-13) — the same free-form string {@code PipelineEngine} builds for {@code
 * FAILED(scope)} ({@code "write(s) outside allowed_write: a, b, c"}), the one place that names
 * them. Feeds the run view's "roll back excess" confirmation dialog; a HEAD-move entry (not a
 * real path — see {@code GitScopeDiff}) is filtered out since it can't be rolled back this way.
 */
final class ScopeIncident {

    private static final String PREFIX = "write(s) outside allowed_write: ";

    private ScopeIncident() {
    }

    /** Paths from the latest {@code incident.scope} event recorded against {@code stepId}, or
     * empty if there is none (or it doesn't have the expected shape). */
    static List<String> violatingPaths(List<AuditEvent> audit, String stepId) {
        String detail = "";
        for (AuditEvent event : audit) {
            if (event.type().equals("incident.scope") && stepId.equals(event.stepId())) {
                var node = event.payload().get("detail");
                if (node != null && node.isTextual()) {
                    detail = node.asText(); // later entries overwrite — latest wins
                }
            }
        }
        if (!detail.startsWith(PREFIX)) {
            return List.of();
        }
        String rest = detail.substring(PREFIX.length());
        return Arrays.stream(rest.split(", "))
                .map(String::strip)
                .filter(s -> !s.isEmpty() && !s.startsWith(".git ("))
                .toList();
    }
}
