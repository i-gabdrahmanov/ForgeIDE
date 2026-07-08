package dev.forgeide.runtime.logtail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * {@code tail -f} on a virtual thread (SD §7): retry-polls until {@code file} exists (a step's
 * log file doesn't exist before it starts), then keeps one {@link BufferedReader} open and
 * repeatedly calls {@code readLine()} — a partial trailing line is left buffered inside the
 * reader for free, no NIO {@code WatchService} (polling-based on macOS anyway). Every line goes
 * into the caller's {@link LineRingBuffer} (bounded, drop-oldest) and the caller's {@link
 * TailBatcher} (100ms batching); a non-empty batch invokes {@code onBatch} — the <b>caller</b>
 * (the UI) is responsible for hopping to the FX thread, this class never touches it.
 *
 * <p>One instance per (file, iteration): a step's log directory is already per-iteration (see
 * {@code core.run.RunLogLayout}), so there is no rotation to handle within one tailer's lifetime
 * — a new iteration means a brand-new {@code FileTailer}.
 */
public final class FileTailer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FileTailer.class);
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(75);

    private final Path file;
    private final LineRingBuffer buffer;
    private final TailBatcher batcher = new TailBatcher();
    private final Consumer<TailBatcher.TailBatch> onBatch;
    private final Duration pollInterval;
    private final Thread thread;
    private volatile boolean running = true;

    public FileTailer(Path file, LineRingBuffer buffer, Consumer<TailBatcher.TailBatch> onBatch) {
        this(file, buffer, onBatch, DEFAULT_POLL_INTERVAL);
    }

    public FileTailer(Path file, LineRingBuffer buffer, Consumer<TailBatcher.TailBatch> onBatch, Duration pollInterval) {
        this.file = file;
        this.buffer = buffer;
        this.onBatch = onBatch;
        this.pollInterval = pollInterval;
        this.thread = Thread.ofVirtual().name("forgeide-tail-" + file.getFileName()).start(this::loop);
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
    }

    private void loop() {
        while (running && !Files.isRegularFile(file)) {
            sleep();
        }
        if (!running) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            while (running) {
                String line = reader.readLine();
                if (line == null) {
                    flush();
                    sleep();
                    continue;
                }
                buffer.add(line);
                batcher.onLine(line, Instant.now()).ifPresent(onBatch);
            }
            flush();
        } catch (IOException e) {
            // The file may have been removed out from under us (rare); nothing more to tail.
            log.debug("tail of {} ended: {}", file, e.toString());
        }
    }

    private void flush() {
        batcher.onTick(Instant.now()).ifPresent(onBatch);
    }

    private void sleep() {
        try {
            Thread.sleep(pollInterval.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }
}
