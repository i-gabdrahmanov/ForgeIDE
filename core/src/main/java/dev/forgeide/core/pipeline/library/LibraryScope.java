package dev.forgeide.core.pipeline.library;

import java.nio.file.Path;
import java.util.Objects;

/**
 * The two tile libraries FR-2.9 asks for: a project one shared via the repo, and a personal one
 * that follows the user across projects.
 */
public enum LibraryScope {

    /** {@code <project>/.forgeide/library/} — travels with the repo (commit it to share with a team). */
    PROJECT,

    /** {@code ~/.forgeide/library/} — this machine's user, every project. */
    USER;

    /** @param projectRoot required for {@link #PROJECT}, ignored for {@link #USER}. */
    public Path directory(Path projectRoot) {
        return switch (this) {
            case PROJECT -> {
                Objects.requireNonNull(projectRoot, "projectRoot required for a PROJECT-scope library");
                yield projectRoot.resolve(".forgeide").resolve("library");
            }
            case USER -> Path.of(System.getProperty("user.home"), ".forgeide", "library");
        };
    }
}
