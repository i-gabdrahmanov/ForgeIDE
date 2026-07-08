package dev.forgeide.core.engine;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code expects} validation for agent phases (SDD FR-4.3): every declared artifact path must
 * exist, be non-empty, and — for {@code .json} — parse. Runs without judge involvement; a
 * failure here is {@code FAILED(artifacts)} before any judge ever sees the step.
 */
final class ArtifactValidation {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ArtifactValidation() {
    }

    /** @return a human-readable reason if any artifact is missing/empty/unparsable, else empty */
    static Optional<String> validate(Path projectRoot, List<Path> expected) {
        for (Path relative : expected) {
            Path absolute = projectRoot.resolve(relative);
            if (!Files.exists(absolute)) {
                return Optional.of("missing artifact: " + relative);
            }
            long size;
            try {
                size = Files.size(absolute);
            } catch (IOException e) {
                return Optional.of("unreadable artifact: " + relative);
            }
            if (size == 0) {
                return Optional.of("empty artifact: " + relative);
            }
            String name = relative.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".json")) {
                try {
                    MAPPER.readTree(absolute.toFile());
                } catch (IOException e) {
                    return Optional.of("unparsable json artifact: " + relative);
                }
            } else if (name.endsWith(".md")) {
                try {
                    Files.readString(absolute);
                } catch (IOException e) {
                    return Optional.of("unreadable md artifact: " + relative);
                }
            }
        }
        return Optional.empty();
    }
}
