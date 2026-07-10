package dev.forgeide.core.pipeline.edit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** T23/FR-2.8: a fresh agent tile's seeded prompt must carry the §5.2 result contract with the
 * step's own id baked in, and never leak the {@code {{step_id}}} placeholder verbatim. */
class AgentPromptScaffoldTest {

    @Test
    void bakesTheStepIdIntoTheContractBlock() {
        String rendered = AgentPromptScaffold.render("lite-ground");

        assertThat(rendered).contains("\"step_id\": \"lite-ground\"");
        assertThat(rendered).doesNotContain("{{step_id}}");
    }

    @Test
    void containsTheFullResultContractShapeAndTheOutwardActionsRule() {
        String rendered = AgentPromptScaffold.render("lite-green");

        assertThat(rendered).contains("```json");
        assertThat(rendered).contains("\"status\"");
        assertThat(rendered).contains("\"artifacts\"");
        assertThat(rendered).contains("\"pending_questions\"");
        assertThat(rendered).contains("\"summary\"");
        assertThat(rendered).containsIgnoringCase("outward");
    }
}
