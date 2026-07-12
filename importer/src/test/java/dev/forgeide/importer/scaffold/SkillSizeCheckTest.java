package dev.forgeide.importer.scaffold;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillSizeCheckTest {

    @Test
    void bytesWrapsAnIoFailureAsUnchecked(@TempDir Path dir) {
        Path missing = dir.resolve("does-not-exist");

        assertThatThrownBy(() -> SkillSizeCheck.bytes(missing))
                .isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void sumsRegularFilesUnderTheDirectoryRecursively(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a.txt"), "12345");
        Files.createDirectories(dir.resolve("nested"));
        Files.writeString(dir.resolve("nested/b.txt"), "1234567890");

        assertThat(SkillSizeCheck.bytes(dir)).isEqualTo(5L + 10L);
        assertThat(SkillSizeCheck.isSuspiciouslyLarge(dir)).isFalse();
    }

    /** Same noise filter as {@link ScaffoldScanner#SKIP_DIRS} — a fat {@code .venv} or
     * {@code __pycache__} nobody intends to ship shouldn't trip the warning. */
    @Test
    void ignoresFilesUnderSkipDirs(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("__pycache__"));
        Files.writeString(dir.resolve("__pycache__/cache.pyc"), "x".repeat(1000));

        assertThat(SkillSizeCheck.bytes(dir)).isZero();
    }

    @Test
    void flagsADirectoryLargerThanTheWarnThreshold(@TempDir Path dir) throws IOException {
        writeFileOfSize(dir.resolve("big.bin"), SkillSizeCheck.WARN_THRESHOLD_BYTES + 1);

        assertThat(SkillSizeCheck.isSuspiciouslyLarge(dir)).isTrue();
    }

    private static void writeFileOfSize(Path file, long size) throws IOException {
        byte[] chunk = new byte[64 * 1024];
        try (OutputStream out = Files.newOutputStream(file)) {
            long written = 0;
            while (written < size) {
                int toWrite = (int) Math.min(chunk.length, size - written);
                out.write(chunk, 0, toWrite);
                written += toWrite;
            }
        }
    }
}
