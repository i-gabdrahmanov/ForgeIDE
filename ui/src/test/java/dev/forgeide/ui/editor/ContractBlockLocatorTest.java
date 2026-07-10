package dev.forgeide.ui.editor;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ContractBlockLocatorTest {

    private static final String CONTRACT = """
            Do the thing, then reply with:

            ```json
            {
              "step_id": "lite-green",
              "status": "done | blocked",
              "artifacts": ["src/main/java/..."],
              "pending_questions": [],
              "summary": "..."
            }
            ```
            """;

    @Test
    void locatesTheFencedJsonBlockContainingStepId() {
        Optional<ContractBlockLocator.TextRange> found = ContractBlockLocator.locate(CONTRACT);

        assertThat(found).isPresent();
        String slice = CONTRACT.substring(found.get().start(), found.get().end());
        assertThat(slice).startsWith("```json").endsWith("```").contains("step_id");
    }

    @Test
    void emptyWhenThereIsNoJsonBlockAtAll() {
        assertThat(ContractBlockLocator.locate("Just do the thing, no contract here.")).isEmpty();
    }

    @Test
    void emptyWhenAJsonBlockExistsButHasNoStepId() {
        String text = """
                Example config:
                ```json
                {"foo": "bar"}
                ```
                """;

        assertThat(ContractBlockLocator.locate(text)).isEmpty();
    }

    @Test
    void picksTheLastStepIdBlockWhenSeveralJsonBlocksArePresent() {
        String text = """
                Example only, not the contract:
                ```json
                {"step_id": "example-not-real"}
                ```

                The real contract:
                ```json
                {"step_id": "lite-green", "status": "done"}
                ```
                """;

        Optional<ContractBlockLocator.TextRange> found = ContractBlockLocator.locate(text);

        assertThat(found).isPresent();
        assertThat(text.substring(found.get().start(), found.get().end())).contains("lite-green");
    }

    @Test
    void contractSurvivesIsTrueWhenTheOldTextHadNoContractAtAll() {
        assertThat(ContractBlockLocator.contractSurvives("no contract here", "still none")).isTrue();
    }

    @Test
    void contractSurvivesIsFalseWhenTheEditDropsAnExistingContract() {
        assertThat(ContractBlockLocator.contractSurvives(CONTRACT, "the contract block got deleted")).isFalse();
    }

    @Test
    void contractSurvivesIsTrueWhenTheContractIsStillPresentAfterAnEdit() {
        String edited = CONTRACT + "\nOne more instruction.\n";

        assertThat(ContractBlockLocator.contractSurvives(CONTRACT, edited)).isTrue();
    }
}
