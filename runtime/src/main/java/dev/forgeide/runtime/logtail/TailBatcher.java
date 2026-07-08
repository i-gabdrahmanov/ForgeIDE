package dev.forgeide.runtime.logtail;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Decides when a 100ms batch window (SD §7) has elapsed (SD §7: "батчинг строк (раз в 100 мс)").
 * Pure and driven entirely by caller-supplied {@link Instant}s — no real sleeping, no
 * wall-clock reads — so it's unit-testable without flaky timing. Not thread-safe: {@link
 * FileTailer} only ever calls it from its own single tailing thread.
 */
public final class TailBatcher {

    public static final Duration DEFAULT_WINDOW = Duration.ofMillis(100);

    private final Duration window;
    private final List<String> pending = new ArrayList<>();
    private Instant windowStart;

    public TailBatcher() {
        this(DEFAULT_WINDOW);
    }

    public TailBatcher(Duration window) {
        this.window = Objects.requireNonNull(window, "window");
    }

    public record TailBatch(List<String> newLines) {
        public TailBatch {
            newLines = List.copyOf(newLines);
        }
    }

    /** A line just arrived; may itself complete a window that was already due to flush. */
    public Optional<TailBatch> onLine(String line, Instant now) {
        if (pending.isEmpty()) {
            windowStart = now;
        }
        pending.add(line);
        return flushIfDue(now);
    }

    /** No new line, just a chance to flush a window that's been open long enough. */
    public Optional<TailBatch> onTick(Instant now) {
        return flushIfDue(now);
    }

    private Optional<TailBatch> flushIfDue(Instant now) {
        if (pending.isEmpty() || windowStart == null) {
            return Optional.empty();
        }
        if (Duration.between(windowStart, now).compareTo(window) < 0) {
            return Optional.empty();
        }
        TailBatch batch = new TailBatch(List.copyOf(pending));
        pending.clear();
        windowStart = null;
        return Optional.of(batch);
    }
}
