package dev.forgeide.ui.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.forgeide.core.audit.AuditEvent;
import dev.forgeide.core.run.RunId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScopeIncidentTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RunId runId = RunId.newId();

    @Test
    void parsesTheFilesOutOfTheScopeIncidentDetail() {
        List<AuditEvent> audit = List.of(event("lite-green", "incident.scope",
                "write(s) outside allowed_write: outside/leak.txt, secrets/other.txt"));

        assertThat(ScopeIncident.violatingPaths(audit, "lite-green"))
                .containsExactly("outside/leak.txt", "secrets/other.txt");
    }

    @Test
    void dropsTheHeadMoveSyntheticEntryItCannotRollBack() {
        List<AuditEvent> audit = List.of(event("lite-green", "incident.scope",
                "write(s) outside allowed_write: .git (HEAD moved abc -> def), src/a.txt"));

        assertThat(ScopeIncident.violatingPaths(audit, "lite-green")).containsExactly("src/a.txt");
    }

    @Test
    void ignoresEventsForOtherStepsAndOtherTypes() {
        List<AuditEvent> audit = List.of(
                event("other-step", "incident.scope", "write(s) outside allowed_write: x.txt"),
                event("lite-green", "step.failed", "SCOPE"));

        assertThat(ScopeIncident.violatingPaths(audit, "lite-green")).isEmpty();
    }

    @Test
    void latestScopeIncidentWinsOverAnEarlierOne() {
        List<AuditEvent> audit = List.of(
                event("lite-green", "incident.scope", "write(s) outside allowed_write: first.txt"),
                event("lite-green", "incident.scope", "write(s) outside allowed_write: second.txt"));

        assertThat(ScopeIncident.violatingPaths(audit, "lite-green")).containsExactly("second.txt");
    }

    private AuditEvent event(String stepId, String type, String detail) {
        ObjectNode payload = mapper.createObjectNode().put("detail", detail);
        return new AuditEvent(1, Instant.parse("2026-07-08T00:00:00Z"), runId, stepId, 0, type, payload, "", "");
    }
}
