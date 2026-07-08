package dev.forgeide.runtime.logtail;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Driven entirely by injected {@link Instant}s — no real sleeping, so this asserts on exact edges. */
class TailBatcherTest {

    private static final Duration WINDOW = TailBatcher.DEFAULT_WINDOW;
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void linesWithinTheWindowDoNotFlushUntilItElapses() {
        TailBatcher batcher = new TailBatcher();

        assertThat(batcher.onLine("a", T0)).isEmpty();
        assertThat(batcher.onLine("b", T0.plusMillis(50))).isEmpty();
        Optional<TailBatcher.TailBatch> batch = batcher.onLine("c", T0.plus(WINDOW));

        assertThat(batch).isPresent();
        assertThat(batch.get().newLines()).containsExactly("a", "b", "c");
    }

    @Test
    void onTickFlushesAPendingWindowWithNoNewLine() {
        TailBatcher batcher = new TailBatcher();
        batcher.onLine("a", T0);

        assertThat(batcher.onTick(T0.plusMillis(50))).isEmpty();
        Optional<TailBatcher.TailBatch> batch = batcher.onTick(T0.plus(WINDOW));

        assertThat(batch).isPresent();
        assertThat(batch.get().newLines()).containsExactly("a");
    }

    @Test
    void onTickWithNothingPendingNeverFlushes() {
        TailBatcher batcher = new TailBatcher();

        assertThat(batcher.onTick(T0.plus(Duration.ofSeconds(10)))).isEmpty();
    }

    @Test
    void aFlushedWindowStartsFreshForTheNextLine() {
        TailBatcher batcher = new TailBatcher();
        Instant secondWindowStart = T0.plus(WINDOW).plusMillis(1);
        batcher.onLine("a", T0);
        batcher.onLine("b", T0.plus(WINDOW)).orElseThrow(); // flush #1

        assertThat(batcher.onLine("c", secondWindowStart)).isEmpty();
        Optional<TailBatcher.TailBatch> batch = batcher.onLine("d", secondWindowStart.plus(WINDOW));

        assertThat(batch).isPresent();
        assertThat(batch.get().newLines()).containsExactly("c", "d");
    }

    @Test
    void chattyBurstsStillBatchAtTheWindowBoundaryNotPerLine() {
        TailBatcher batcher = new TailBatcher();
        List<Optional<TailBatcher.TailBatch>> results = new java.util.ArrayList<>();
        // 1000 lines spread over 1 simulated second (1ms apart) against a 100ms window:
        // roughly one flush every 100 lines, never one flush per line.
        for (int i = 0; i < 1000; i++) {
            results.add(batcher.onLine("line-" + i, T0.plusMillis(i)));
        }

        long flushes = results.stream().filter(Optional::isPresent).count();
        int totalLinesFlushed = results.stream().filter(Optional::isPresent)
                .mapToInt(b -> b.get().newLines().size()).sum();
        assertThat(flushes).isBetween(8L, 12L);
        assertThat(totalLinesFlushed).isLessThan(1000).isGreaterThan(900);
    }
}
