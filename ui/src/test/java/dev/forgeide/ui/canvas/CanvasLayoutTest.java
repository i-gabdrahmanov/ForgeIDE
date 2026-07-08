package dev.forgeide.ui.canvas;

import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.yaml.PipelineTemplates;
import dev.forgeide.core.pipeline.yaml.PipelineYaml;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CanvasLayoutTest {

    private final PipelineYaml yaml = new PipelineYaml();

    @Test
    void forgeliteStepsLandInDependencyOrderColumns() {
        PipelineDefinition pipeline = PipelineTemplates.forgelite();

        CanvasLayout.Result result = CanvasLayout.layout(pipeline);

        Map<String, CanvasLayout.Position> positions = result.positions();
        assertThat(positions).containsOnlyKeys("lite-ground", "lite-design", "gate-design", "lite-red",
                "judge-red", "lite-green", "judge-coverage", "gate-deliver", "deliver");
        // a straight chain: every step is strictly after everything it depends on
        assertThat(positions.get("lite-ground").column()).isEqualTo(0);
        assertThat(positions.get("deliver").column()).isEqualTo(8);
        for (int i = 1; i < 9; i++) {
            assertThat(positions.values()).allSatisfy(p -> assertThat(p.column()).isBetween(0, 8));
        }
        assertThat(result.columnCount()).isEqualTo(9);
        assertThat(result.rowCount()).isEqualTo(1); // pure chain — one tile per column
    }

    @Test
    void everyDependsOnBecomesAnEdge() {
        PipelineDefinition pipeline = PipelineTemplates.forgelite();

        CanvasLayout.Result result = CanvasLayout.layout(pipeline);

        assertThat(result.edges()).contains(
                new CanvasLayout.Edge("lite-ground", "lite-design", CanvasLayout.EdgeKind.DEPENDS_ON, ""),
                new CanvasLayout.Edge("gate-deliver", "deliver", CanvasLayout.EdgeKind.DEPENDS_ON, ""));
    }

    @Test
    void branchRoutesBecomeLabelledEdgesAndOrderColumns() {
        PipelineDefinition pipeline = parse("""
                version: 1
                id: branchy
                steps:
                  - id: gate
                    type: gate
                    question: "ship?"
                    options: [yes, no]
                  - id: route
                    type: branch
                    depends_on: [gate]
                    routes: {yes: ship, no: rework}
                  - id: ship
                    type: outward
                    depends_on: [judge]
                    actions: [git_push]
                  - id: rework
                    type: agent
                    runtime: gigacode
                    prompt: prompts/rework.md
                  - id: judge
                    type: judge
                    target: rework
                    depends_on: [rework]
                    check: {command: [python3, c.py]}
                """);

        CanvasLayout.Result result = CanvasLayout.layout(pipeline);

        assertThat(result.edges()).contains(
                new CanvasLayout.Edge("route", "ship", CanvasLayout.EdgeKind.BRANCH_ROUTE, "yes"),
                new CanvasLayout.Edge("route", "rework", CanvasLayout.EdgeKind.BRANCH_ROUTE, "no"));
        // "ship" only declares depends_on: [judge] but is also branch-routed from "route" —
        // the routing edge must push it at least one column past "route".
        int routeColumn = result.positions().get("route").column();
        int shipColumn = result.positions().get("ship").column();
        assertThat(shipColumn).isGreaterThan(routeColumn);
    }

    @Test
    void danglingAndCyclicReferencesDoNotHangOrCrash() {
        PipelineDefinition pipeline = new PipelineYaml().parseLenient("""
                version: 1
                id: broken
                steps:
                  - id: a
                    type: agent
                    runtime: gigacode
                    prompt: prompts/a.md
                    depends_on: [b, ghost]
                  - id: b
                    type: agent
                    runtime: gigacode
                    prompt: prompts/b.md
                    depends_on: [a]
                """).definition().orElseThrow();

        CanvasLayout.Result result = CanvasLayout.layout(pipeline);

        assertThat(result.positions()).containsOnlyKeys("a", "b");
        // no edge is fabricated for the dangling "ghost" reference
        assertThat(result.edges()).extracting(CanvasLayout.Edge::to).doesNotContain("ghost");
    }

    @Test
    void emptyPipelineYieldsEmptyLayout() {
        PipelineDefinition pipeline = new PipelineDefinition("empty", 1, List.of());

        CanvasLayout.Result result = CanvasLayout.layout(pipeline);

        assertThat(result.positions()).isEmpty();
        assertThat(result.edges()).isEmpty();
        assertThat(result.columnCount()).isEqualTo(0);
        assertThat(result.rowCount()).isEqualTo(0);
        assertThat(result.width()).isEqualTo(0);
        assertThat(result.height()).isEqualTo(0);
    }

    @Test
    void largeFanOutStaysWellFormedAndFast() {
        StringBuilder src = new StringBuilder("""
                version: 1
                id: big
                steps:
                  - id: root
                    type: agent
                    runtime: gigacode
                    prompt: prompts/root.md
                """);
        for (int i = 0; i < 150; i++) {
            src.append("  - id: leaf-").append(i).append('\n')
                    .append("    type: agent\n    runtime: gigacode\n    prompt: prompts/leaf.md\n")
                    .append("    depends_on: [root]\n");
        }
        PipelineDefinition pipeline = yaml.parse(src.toString());

        long start = System.nanoTime();
        CanvasLayout.Result result = CanvasLayout.layout(pipeline);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(result.positions()).hasSize(151);
        assertThat(result.columnCount()).isEqualTo(2);
        assertThat(result.rowCount()).isEqualTo(150);
        assertThat(elapsedMs).isLessThan(1000);
    }

    private PipelineDefinition parse(String source) {
        return yaml.parse(source);
    }
}
