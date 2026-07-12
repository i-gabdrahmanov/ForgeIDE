package dev.forgeide.importer.bind;

import dev.forgeide.importer.scaffold.PromptSection;

import java.nio.file.Path;
import java.util.List;
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

    /**
     * T33 (SD §8, ревью импортёра 2026-07-11 №2): {@link ImportBinder} found more than one
     * {@link PromptSection} whose heading mentions the step id and refuses to guess by picking
     * the first one — the user resolves it by choosing a candidate ({@code
     * ImportSession.resolveAmbiguous}), same §-section picker manual binding uses for a
     * heading-bearing file.
     *
     * @param candidates every section that matched, in scan order — always at least two
     */
    record Ambiguous(String key, Path targetPath, List<PromptSection> candidates) implements TileBinding {
        public Ambiguous {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(targetPath, "targetPath");
            candidates = List.copyOf(candidates);
            if (candidates.size() < 2) {
                throw new IllegalArgumentException(
                        "ambiguous binding needs at least 2 candidates, got " + candidates.size());
            }
        }
    }
}
