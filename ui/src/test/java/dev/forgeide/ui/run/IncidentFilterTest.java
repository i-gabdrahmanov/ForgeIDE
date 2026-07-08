package dev.forgeide.ui.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.forgeide.core.audit.AuditEvent;
import dev.forgeide.core.run.RunId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentFilterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RunId runId = RunId.newId();

    @Test
    void incidentRaisedIsAlwaysAnIncident() {
        assertThat(IncidentFilter.isIncident(event("incident.raised", payload("SCRIPT")))).isTrue();
    }

    @Test
    void stepFailedIsAnIncidentOnlyForScopeOrTamperedReasons() {
        assertThat(IncidentFilter.isIncident(event("step.failed", payload("SCOPE")))).isTrue();
        assertThat(IncidentFilter.isIncident(event("step.failed", payload("TAMPERED")))).isTrue();
        assertThat(IncidentFilter.isIncident(event("step.failed", payload("SCRIPT")))).isFalse();
        assertThat(IncidentFilter.isIncident(event("step.failed", payload("BUDGET")))).isFalse();
    }

    @Test
    void runPausedIsAnIncidentOnlyForHarnessDrift() {
        assertThat(IncidentFilter.isIncident(event("run.paused", payload("HARNESS_DRIFT")))).isTrue();
        assertThat(IncidentFilter.isIncident(event("run.paused", payload("ENGINE_ERROR")))).isFalse();
    }

    @Test
    void ordinaryLifecycleEventsAreNeverIncidents() {
        assertThat(IncidentFilter.isIncident(event("step.completed", mapper.createObjectNode()))).isFalse();
        assertThat(IncidentFilter.isIncident(event("run.started", mapper.createObjectNode()))).isFalse();
    }

    private ObjectNode payload(String reason) {
        return mapper.createObjectNode().put("reason", reason);
    }

    private AuditEvent event(String type, ObjectNode payload) {
        return new AuditEvent(1, Instant.parse("2026-07-08T00:00:00Z"), runId, "step", 0, type, payload, "", "");
    }
}
