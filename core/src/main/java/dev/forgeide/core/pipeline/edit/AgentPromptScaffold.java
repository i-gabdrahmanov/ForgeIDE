package dev.forgeide.core.pipeline.edit;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * T23/FR-2.8: the prompt text a freshly created agent tile is seeded with — a scaffold carrying
 * the mandatory result-contract block (SDD §5.2: {@code step_id/status/artifacts/
 * pending_questions/summary}, plus the "no self-service outward actions" rule) so the tile is
 * never left with an empty prompt the engine cannot parse. {@code {{step_id}}} is a plain
 * substitution done here, not the app's {@code ${scope.key}} template language ({@link
 * dev.forgeide.core.vars.Variables}) — this text is written to disk once, at creation time, so it
 * must come out with the literal id baked in rather than a reference the engine would try (and
 * fail) to resolve at run time.
 */
public final class AgentPromptScaffold {

    private static final String RESOURCE = "/dev/forgeide/core/pipeline/templates/agent-scratch-prompt.md";
    private static final String PLACEHOLDER = "{{step_id}}";

    private AgentPromptScaffold() {
    }

    public static String render(String stepId) {
        return template().replace(PLACEHOLDER, stepId);
    }

    private static String template() {
        try (InputStream in = AgentPromptScaffold.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing bundled template: " + RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read template: " + RESOURCE, e);
        }
    }
}
