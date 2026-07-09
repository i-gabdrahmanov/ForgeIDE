package dev.forgeide.runtime.state;

import dev.forgeide.core.run.StepStatus;

/**
 * IDE step status → {@code pipeline-state} Forge manifest status (SD §4, T15). The real Forge
 * hooks (tdd-guard, state-recorder, risk_ladder's {@code manifest_status}/{@code
 * active_step_id}) branch on all five schema values — {@code pending}, {@code in_progress},
 * {@code completed}, {@code failed}, {@code skipped} — not just completed/pending, so the
 * projection maps onto the full set rather than collapsing everything but PASSED to "pending".
 */
final class ManifestStatus {

    private ManifestStatus() {
    }

    static String of(StepStatus status) {
        return switch (status) {
            case PASSED -> "completed";
            case RUNNING -> "in_progress";
            case FAILED -> "failed";
            case SKIPPED -> "skipped";
            case PENDING, READY, WAITING_GATE, WAITING_INPUT -> "pending";
        };
    }
}
