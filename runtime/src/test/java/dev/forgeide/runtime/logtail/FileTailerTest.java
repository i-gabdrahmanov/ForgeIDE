package dev.forgeide.runtime.logtail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Narrow live-IO smoke test — real file, real virtual thread, real (short) polling interval. No
 * strict timing assertions: just "does it eventually see lines appended after it started
 * tailing", including before the file exists at all.
 */
class FileTailerTest {

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void picksUpLinesAppendedAfterTailingStartsEvenBeforeTheFileExists(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("stdout.jsonl");
        LineRingBuffer buffer = new LineRingBuffer();
        List<String> batches = new CopyOnWriteArrayList<>();

        try (FileTailer tailer = new FileTailer(file, buffer, batch -> batches.addAll(batch.newLines()),
                Duration.ofMillis(20))) {
            spinSleep(Duration.ofMillis(100)); // tailer is polling for a file that doesn't exist yet

            Files.writeString(file, "line-1\nline-2\n", StandardOpenOption.CREATE);
            awaitUntil(() -> buffer.snapshot().containsAll(List.of("line-1", "line-2")));

            Files.writeString(file, "line-3\n", StandardOpenOption.APPEND);
            awaitUntil(() -> buffer.snapshot().contains("line-3"));

            awaitUntil(() -> batches.containsAll(List.of("line-1", "line-2", "line-3")));
            assertThat(buffer.droppedCount()).isZero();
        }
    }

    private static void awaitUntil(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 5s");
            }
            spinSleep(Duration.ofMillis(10));
        }
    }

    private static void spinSleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
