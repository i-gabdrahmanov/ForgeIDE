package dev.forgeide.importer.scaffold;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * T34: total on-disk size of a scanned skill directory, minus {@link ScaffoldScanner#SKIP_DIRS}
 * noise — the importer now copies a skill's directory whole, so the UI warns before import if
 * that turns out to be a lot more than {@code SKILL.md} + a few prompt/script files (a binary or
 * data file accidentally sitting in the checkout).
 */
public final class SkillSizeCheck {

    public static final long WARN_THRESHOLD_BYTES = 10L * 1024 * 1024;

    private SkillSizeCheck() {
    }

    public static long bytes(Path skillDir) {
        try (Stream<Path> walk = Files.walk(skillDir)) {
            long total = 0;
            for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile)
                    .filter(p -> !ScaffoldScanner.isUnderSkippedDir(skillDir, p))::iterator) {
                total += Files.size(file);
            }
            return total;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot size " + skillDir, e);
        }
    }

    public static boolean isSuspiciouslyLarge(Path skillDir) {
        return bytes(skillDir) > WARN_THRESHOLD_BYTES;
    }
}
