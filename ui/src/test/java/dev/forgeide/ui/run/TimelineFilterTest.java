package dev.forgeide.ui.run;

import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TimelineFilterTest {

    private static final Instant TS = Instant.parse("2026-07-08T00:00:00Z");

    @Test
    void noFiltersReturnsEverything() {
        List<TimelineRow> rows = List.of(row(1, "a", "step.running", false), row(2, "b", "step.failed", true));

        assertThat(TimelineFilter.apply(rows, Optional.empty(), Optional.empty(), false)).hasSize(2);
    }

    @Test
    void filtersByStep() {
        List<TimelineRow> rows = List.of(row(1, "a", "step.running", false), row(2, "b", "step.running", false));

        assertThat(TimelineFilter.apply(rows, Optional.of("a"), Optional.empty(), false))
                .extracting(TimelineRow::stepId).containsExactly("a");
    }

    @Test
    void filtersByType() {
        List<TimelineRow> rows = List.of(row(1, "a", "step.running", false), row(2, "a", "step.completed", false));

        assertThat(TimelineFilter.apply(rows, Optional.empty(), Optional.of("step.completed"), false))
                .extracting(TimelineRow::type).containsExactly("step.completed");
    }

    @Test
    void onlyIncidentsKeepsIncidentRowsOnly() {
        List<TimelineRow> rows = List.of(row(1, "a", "step.completed", false), row(2, "a", "step.failed", true));

        assertThat(TimelineFilter.apply(rows, Optional.empty(), Optional.empty(), true))
                .extracting(TimelineRow::seq).containsExactly(2L);
    }

    @Test
    void filtersCompose() {
        List<TimelineRow> rows = List.of(
                row(1, "a", "step.failed", true),
                row(2, "b", "step.failed", true),
                row(3, "a", "step.completed", false));

        assertThat(TimelineFilter.apply(rows, Optional.of("a"), Optional.empty(), true))
                .extracting(TimelineRow::seq).containsExactly(1L);
    }

    private TimelineRow row(long seq, String stepId, String type, boolean incident) {
        return new TimelineRow(seq, TS, stepId, 0, type, NullNode.getInstance(), incident);
    }
}
