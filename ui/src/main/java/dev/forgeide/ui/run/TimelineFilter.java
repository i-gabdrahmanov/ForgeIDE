package dev.forgeide.ui.run;

import java.util.List;
import java.util.Optional;

/** Step / event-type / "only incidents" filtering for {@link TimelineRow}s (SDD FR-7.6). */
public final class TimelineFilter {

    private TimelineFilter() {
    }

    public static List<TimelineRow> apply(List<TimelineRow> rows, Optional<String> stepId,
                                           Optional<String> type, boolean onlyIncidents) {
        return rows.stream()
                .filter(r -> stepId.isEmpty() || stepId.get().equals(r.stepId()))
                .filter(r -> type.isEmpty() || type.get().equals(r.type()))
                .filter(r -> !onlyIncidents || r.incident())
                .toList();
    }
}
