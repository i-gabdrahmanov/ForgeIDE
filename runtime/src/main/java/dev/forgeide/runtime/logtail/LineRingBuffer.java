package dev.forgeide.runtime.logtail;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Bounded, drop-oldest line buffer (SD §7: "кольцевой буфер 10k строк на шаг" — protects the UI
 * from OOM on a gradle-chatty phase). Thread-safe: written from a {@link FileTailer}'s own
 * thread, read from the FX thread for the initial catch-up snapshot when a log tab is opened.
 */
public final class LineRingBuffer {

    public static final int DEFAULT_CAPACITY = 10_000;

    private final int capacity;
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private long dropped;

    public LineRingBuffer() {
        this(DEFAULT_CAPACITY);
    }

    public LineRingBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
    }

    public synchronized void add(String line) {
        if (lines.size() >= capacity) {
            lines.pollFirst();
            dropped++;
        }
        lines.addLast(line);
    }

    public synchronized List<String> snapshot() {
        return List.copyOf(lines);
    }

    /** Lines discarded from the front of the buffer because {@link #add} was called past capacity. */
    public synchronized long droppedCount() {
        return dropped;
    }
}
