package dev.forgeide.ui.run;

import com.fasterxml.jackson.databind.JsonNode;
import dev.forgeide.core.audit.AuditEvent;

import java.time.Instant;

/** UI-friendly projection of one {@link AuditEvent}, with {@link IncidentFilter} precomputed. */
public record TimelineRow(long seq, Instant ts, String stepId, int iteration, String type,
                           JsonNode payload, boolean incident) {

    public static TimelineRow of(AuditEvent event) {
        return new TimelineRow(event.seq(), event.ts(), event.stepId(), event.iteration(), event.type(),
                event.payload(), IncidentFilter.isIncident(event));
    }
}
