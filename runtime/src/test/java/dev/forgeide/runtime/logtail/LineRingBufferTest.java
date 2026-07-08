package dev.forgeide.runtime.logtail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class LineRingBufferTest {

    @Test
    void snapshotReturnsLinesInInsertionOrder() {
        LineRingBuffer buffer = new LineRingBuffer(10);

        buffer.add("a");
        buffer.add("b");
        buffer.add("c");

        assertThat(buffer.snapshot()).containsExactly("a", "b", "c");
        assertThat(buffer.droppedCount()).isZero();
    }

    @Test
    void pastCapacityDropsOldestAndCountsDrops() {
        LineRingBuffer buffer = new LineRingBuffer(3);

        buffer.add("a");
        buffer.add("b");
        buffer.add("c");
        buffer.add("d");
        buffer.add("e");

        assertThat(buffer.snapshot()).containsExactly("c", "d", "e");
        assertThat(buffer.droppedCount()).isEqualTo(2);
    }

    @Test
    void rejectsNonPositiveCapacity() {
        assertThatIllegalArgumentException().isThrownBy(() -> new LineRingBuffer(0));
    }
}
