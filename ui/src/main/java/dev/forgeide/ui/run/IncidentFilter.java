package dev.forgeide.ui.run;

import com.fasterxml.jackson.databind.JsonNode;
import dev.forgeide.core.audit.AuditEvent;

import java.util.Set;

/**
 * "Only incidents" predicate for the Timeline (SDD FR-7.6 acceptance: "фильтр «только инциденты»
 * показывает tamper/scope/drift события"). Nothing in the engine produces {@code SCOPE}/{@code
 * TAMPERED}/{@code HARNESS_DRIFT} yet (that's T14/T16/T18) — this predicate is ready for them and
 * is exercised here against hand-built {@link AuditEvent} fixtures; {@code incident.raised} (T10's
 * own {@code PipelineEngine.haltOnEngineError}) already fires for real today.
 */
public final class IncidentFilter {

    private static final Set<String> INCIDENT_FAILURE_REASONS = Set.of("SCOPE", "TAMPERED");

    private IncidentFilter() {
    }

    public static boolean isIncident(AuditEvent event) {
        return switch (event.type()) {
            case "incident.raised" -> true;
            case "step.failed" -> INCIDENT_FAILURE_REASONS.contains(reasonOf(event));
            case "run.paused" -> "HARNESS_DRIFT".equals(reasonOf(event));
            default -> false;
        };
    }

    private static String reasonOf(AuditEvent event) {
        JsonNode reason = event.payload().get("reason");
        return reason != null && reason.isTextual() ? reason.asText() : "";
    }
}
