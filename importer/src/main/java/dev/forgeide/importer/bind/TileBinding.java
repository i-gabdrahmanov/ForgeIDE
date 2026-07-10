package dev.forgeide.importer.bind;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One file a template step needs (a prompt, or a judge's deterministic check script) and whether
 * {@link ImportBinder} found it in the scanned scaffold (SD §8: "Несматченные плитки
 * подсвечиваются: пользователь указывает файл вручную"). {@link #key} identifies the need within
 * one template — the step id for an agent prompt, {@code <judgeId>.check}/{@code <judgeId>.llm}
 * for a judge's two possible file needs (same suffix convention {@code LibraryTileInsertion}
 * already uses for saved-tile files). {@link #targetPath} is always the path already baked into
 * the template (e.g. {@code prompts/lite-ground.md}) — importing never renames it, it only
 * supplies the file content that path should hold in the target project.
 */
public sealed interface TileBinding {

    String key();

    Path targetPath();

    /**
     * @param sourcePath the scaffold file the content came from — a whole file for a check
     *                   script, or the {@code subagent-prompts.md} a prompt slice was cut out of
     *                   (its parent skill directory is what attributes the step to a registry
     *                   entry, T24's validity-badge wiring)
     * @param content    the resolved file text, ready to write at {@link #targetPath}
     */
    record Matched(String key, Path targetPath, Path sourcePath, String content) implements TileBinding {
        public Matched {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(targetPath, "targetPath");
            Objects.requireNonNull(sourcePath, "sourcePath");
            Objects.requireNonNull(content, "content");
        }
    }

    /** @param hint what the binder looked for, shown next to the highlighted tile so a manual pick knows what to find */
    record Unmatched(String key, Path targetPath, String hint) implements TileBinding {
        public Unmatched {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(targetPath, "targetPath");
            Objects.requireNonNull(hint, "hint");
        }
    }
}
