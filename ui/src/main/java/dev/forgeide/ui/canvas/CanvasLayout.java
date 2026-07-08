package dev.forgeide.ui.canvas;

import dev.forgeide.core.pipeline.BranchStep;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.StepDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Topological-column layout for the read-only canvas (SD §7, T05): a step's column is the
 * length of its longest dependency chain from an entry step ({@code depends_on}, plus branch
 * {@code routes} — a forward edge not reflected in {@code depends_on}); rows within a column are
 * ordered with a short barycenter pass over neighbouring columns to cut down edge crossings
 * ("упрощённый Sugiyama, минимизация пересечений — базово" per the task scope).
 *
 * <p>Pure model in, pure geometry out — no JavaFX dependency, so the algorithm and its handling
 * of malformed graphs (cycles, dangling references — the canvas must still render something for
 * an invalid {@code pipeline.yaml}, SD M1 acceptance) are unit-testable without a display.
 */
public final class CanvasLayout {

    public static final double TILE_WIDTH = 220;
    public static final double TILE_HEIGHT = 92;
    public static final double COLUMN_GAP = 90;
    public static final double ROW_GAP = 36;

    private static final int CROSSING_REDUCTION_PASSES = 4;

    private CanvasLayout() {
    }

    public enum EdgeKind {
        DEPENDS_ON,
        BRANCH_ROUTE
    }

    public record Position(int column, int row, double x, double y) {
    }

    /** @param label the branch answer for a {@link EdgeKind#BRANCH_ROUTE} edge, else blank */
    public record Edge(String from, String to, EdgeKind kind, String label) {
    }

    public record Result(Map<String, Position> positions, List<Edge> edges, int columnCount, int rowCount) {

        public double width() {
            return columnCount == 0 ? 0 : columnCount * TILE_WIDTH + Math.max(0, columnCount - 1) * COLUMN_GAP;
        }

        public double height() {
            return rowCount == 0 ? 0 : rowCount * TILE_HEIGHT + Math.max(0, rowCount - 1) * ROW_GAP;
        }
    }

    public static Result layout(PipelineDefinition pipeline) {
        List<StepDefinition> steps = pipeline.steps();
        Map<String, StepDefinition> byId = new LinkedHashMap<>();
        steps.forEach(s -> byId.put(s.id(), s));

        Map<String, List<String>> predecessors = predecessorsOf(steps, byId);
        Map<String, List<String>> successors = successorsOf(predecessors);
        Map<String, Integer> columnOf = columnsOf(steps, predecessors);

        Map<Integer, List<String>> byColumn = new TreeMap<>();
        for (StepDefinition step : steps) {
            byColumn.computeIfAbsent(columnOf.get(step.id()), c -> new ArrayList<>()).add(step.id());
        }
        reduceCrossings(byColumn, predecessors, successors);

        Map<String, Position> positions = new LinkedHashMap<>();
        int maxRows = 0;
        for (Map.Entry<Integer, List<String>> entry : byColumn.entrySet()) {
            int column = entry.getKey();
            List<String> ids = entry.getValue();
            maxRows = Math.max(maxRows, ids.size());
            for (int row = 0; row < ids.size(); row++) {
                double x = column * (TILE_WIDTH + COLUMN_GAP);
                double y = row * (TILE_HEIGHT + ROW_GAP);
                positions.put(ids.get(row), new Position(column, row, x, y));
            }
        }
        int columnCount = byColumn.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
        return new Result(positions, edgesOf(steps, byId), columnCount, maxRows);
    }

    // ---- graph construction -------------------------------------------------------------

    /** {@code depends_on} plus, for a {@link BranchStep}, its route targets (dangling refs skipped). */
    private static Map<String, List<String>> predecessorsOf(List<StepDefinition> steps, Map<String, StepDefinition> byId) {
        Map<String, List<String>> predecessors = new LinkedHashMap<>();
        for (StepDefinition step : steps) {
            predecessors.put(step.id(), new ArrayList<>());
        }
        for (StepDefinition step : steps) {
            for (String dep : step.dependsOn()) {
                if (byId.containsKey(dep)) {
                    predecessors.get(step.id()).add(dep);
                }
            }
            if (step instanceof BranchStep branch) {
                for (String target : branch.routes().values()) {
                    if (byId.containsKey(target)) {
                        predecessors.get(target).add(step.id());
                    }
                }
            }
        }
        return predecessors;
    }

    private static Map<String, List<String>> successorsOf(Map<String, List<String>> predecessors) {
        Map<String, List<String>> successors = new LinkedHashMap<>();
        predecessors.keySet().forEach(id -> successors.put(id, new ArrayList<>()));
        predecessors.forEach((id, preds) -> preds.forEach(pred -> successors.get(pred).add(id)));
        return successors;
    }

    private static List<Edge> edgesOf(List<StepDefinition> steps, Map<String, StepDefinition> byId) {
        List<Edge> edges = new ArrayList<>();
        for (StepDefinition step : steps) {
            for (String dep : step.dependsOn()) {
                if (byId.containsKey(dep)) {
                    edges.add(new Edge(dep, step.id(), EdgeKind.DEPENDS_ON, ""));
                }
            }
            if (step instanceof BranchStep branch) {
                branch.routes().forEach((answer, target) -> {
                    if (byId.containsKey(target)) {
                        edges.add(new Edge(step.id(), target, EdgeKind.BRANCH_ROUTE, answer));
                    }
                });
            }
        }
        return edges;
    }

    // ---- columns (longest path from an entry step) ---------------------------------------

    private static Map<String, Integer> columnsOf(List<StepDefinition> steps, Map<String, List<String>> predecessors) {
        Map<String, Integer> memo = new HashMap<>();
        Set<String> visiting = new HashSet<>();
        for (StepDefinition step : steps) {
            columnOf(step.id(), predecessors, memo, visiting);
        }
        return memo;
    }

    private static int columnOf(String id, Map<String, List<String>> predecessors,
                                 Map<String, Integer> memo, Set<String> visiting) {
        Integer cached = memo.get(id);
        if (cached != null) {
            return cached;
        }
        if (!visiting.add(id)) {
            return 0; // cycle guard: pipeline is invalid, but the canvas still renders something
        }
        int column = 0;
        for (String pred : predecessors.getOrDefault(id, List.of())) {
            column = Math.max(column, columnOf(pred, predecessors, memo, visiting) + 1);
        }
        visiting.remove(id);
        memo.put(id, column);
        return column;
    }

    // ---- rows (barycenter crossing reduction) ---------------------------------------------

    private static void reduceCrossings(Map<Integer, List<String>> byColumn,
                                         Map<String, List<String>> predecessors,
                                         Map<String, List<String>> successors) {
        if (byColumn.size() < 2) {
            return;
        }
        List<Integer> ascending = new ArrayList<>(byColumn.keySet());
        Collections.sort(ascending);
        List<Integer> descending = new ArrayList<>(ascending);
        Collections.reverse(descending);

        for (int pass = 0; pass < CROSSING_REDUCTION_PASSES; pass++) {
            boolean downward = pass % 2 == 0;
            List<Integer> order = downward ? ascending : descending;
            Map<String, List<String>> neighboursOf = downward ? predecessors : successors;
            Map<String, Integer> rowOf = currentRows(byColumn);
            for (int column : order) {
                List<String> ids = byColumn.get(column);
                sortByBarycenter(ids, neighboursOf, rowOf);
                for (int row = 0; row < ids.size(); row++) {
                    rowOf.put(ids.get(row), row);
                }
            }
        }
    }

    private static Map<String, Integer> currentRows(Map<Integer, List<String>> byColumn) {
        Map<String, Integer> rowOf = new HashMap<>();
        for (List<String> ids : byColumn.values()) {
            for (int row = 0; row < ids.size(); row++) {
                rowOf.put(ids.get(row), row);
            }
        }
        return rowOf;
    }

    /** Stable sort by the average row of each node's already-placed neighbours (ties keep order). */
    private static void sortByBarycenter(List<String> ids, Map<String, List<String>> neighboursOf,
                                          Map<String, Integer> rowOf) {
        Map<String, Double> barycenter = new HashMap<>();
        for (String id : ids) {
            List<String> neighbours = neighboursOf.getOrDefault(id, List.of());
            if (neighbours.isEmpty()) {
                barycenter.put(id, (double) rowOf.getOrDefault(id, 0));
                continue;
            }
            double sum = 0;
            for (String neighbour : neighbours) {
                sum += rowOf.getOrDefault(neighbour, 0);
            }
            barycenter.put(id, sum / neighbours.size());
        }
        ids.sort(Comparator.comparingDouble(barycenter::get));
    }
}
