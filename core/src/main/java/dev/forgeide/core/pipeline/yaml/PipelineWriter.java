package dev.forgeide.core.pipeline.yaml;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.BranchStep;
import dev.forgeide.core.pipeline.GateStep;
import dev.forgeide.core.pipeline.JudgeStep;
import dev.forgeide.core.pipeline.OutwardAction;
import dev.forgeide.core.pipeline.OutwardStep;
import dev.forgeide.core.pipeline.PerTaskLoop;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.PipelineParam;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.core.policy.TokenBudget;

import java.nio.file.Path;
import java.util.List;

/**
 * Serialises a {@link PipelineDefinition} back to YAML. Every field the parser reads is
 * emitted (defaults materialised included) so {@code parse(write(def)).equals(def)}. Comments
 * present in a hand-written file are not preserved — an accepted limitation (SDD FR-2.7).
 */
final class PipelineWriter {

    private final ObjectMapper mapper;

    PipelineWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    String write(PipelineDefinition def) {
        ObjectNode root = mapper.createObjectNode();
        root.put("version", def.version());
        root.put("id", def.id());
        if (!def.params().isEmpty()) {
            ObjectNode params = root.putObject("params");
            for (PipelineParam param : def.params()) {
                ObjectNode spec = params.putObject(param.name());
                spec.put("required", param.required());
                param.hint().ifPresent(h -> spec.put("hint", h));
            }
        }
        ArrayNode steps = root.putArray("steps");
        for (StepDefinition step : def.steps()) {
            steps.add(stepNode(step));
        }
        try {
            return mapper.writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("failed to serialise pipeline", e);
        }
    }

    private ObjectNode stepNode(StepDefinition step) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", step.id());
        switch (step) {
            case AgentStep a -> writeAgent(node, a);
            case ScriptStep s -> writeScript(node, s);
            case JudgeStep j -> writeJudge(node, j);
            case GateStep g -> writeGate(node, g);
            case BranchStep b -> writeBranch(node, b);
            case PerTaskLoop l -> writePerTaskLoop(node, l);
            case OutwardStep o -> writeOutward(node, o);
        }
        return node;
    }

    private void writeAgent(ObjectNode node, AgentStep a) {
        node.put("type", "agent");
        node.put("runtime", a.runtimeRef());
        putDepends(node, a.dependsOn());
        node.put("prompt", a.promptTemplate().toString());
        putPaths(node, "expects", a.expectedArtifacts());
        putStrings(node, "allowed_write", a.allowedWrite());
        node.set("env_scope", strings(a.envScope()));
        node.set("budget", budgetNode(a.budget()));
        ObjectNode retry = node.putObject("retry");
        retry.put("stream", a.retry().stream());
        retry.put("script", a.retry().script());
    }

    private void writeScript(ObjectNode node, ScriptStep s) {
        node.put("type", "script");
        putDepends(node, s.dependsOn());
        node.set("command", strings(s.command()));
        node.put("timeout", Durations.format(s.timeout()));
    }

    private void writeJudge(ObjectNode node, JudgeStep j) {
        node.put("type", "judge");
        putDepends(node, j.dependsOn());
        node.put("target", j.targetStepId());
        j.deterministicCheck().ifPresent(check -> {
            ObjectNode c = node.putObject("check");
            c.set("command", strings(check.command()));
            c.put("timeout", Durations.format(check.timeout()));
        });
        j.llmJudge().ifPresent(llm -> {
            ObjectNode l = node.putObject("llm");
            l.put("runtime", llm.runtimeRef());
            l.put("prompt", llm.promptTemplate().toString());
            l.set("budget", budgetNode(llm.budget()));
        });
        ObjectNode fp = node.putObject("fail_policy");
        fp.put("max_iterations", j.failPolicy().maxIterations());
    }

    private void writeGate(ObjectNode node, GateStep g) {
        node.put("type", "gate");
        putDepends(node, g.dependsOn());
        node.put("question", g.question());
        node.set("options", strings(g.options()));
        putPaths(node, "show", g.showArtifacts());
    }

    private void writeBranch(ObjectNode node, BranchStep b) {
        node.put("type", "branch");
        putDepends(node, b.dependsOn());
        ObjectNode routes = node.putObject("routes");
        b.routes().forEach(routes::put);
    }

    private void writePerTaskLoop(ObjectNode node, PerTaskLoop l) {
        node.put("type", "per_task_loop");
        putDepends(node, l.dependsOn());
        node.put("task_plan", l.taskPlanJson().toString());
        ArrayNode template = node.putArray("template");
        for (StepDefinition nested : l.template()) {
            template.add(stepNode(nested));
        }
    }

    private void writeOutward(ObjectNode node, OutwardStep o) {
        node.put("type", "outward");
        putDepends(node, o.dependsOn());
        ArrayNode actions = node.putArray("actions");
        for (OutwardAction action : o.actions()) {
            actions.add(action.name().toLowerCase());
        }
        node.set("env_scope", strings(o.envScope()));
    }

    // ---- helpers ----------------------------------------------------------------------

    private ObjectNode budgetNode(TokenBudget budget) {
        ObjectNode b = mapper.createObjectNode();
        b.put("tokens", budget.tokens());
        b.put("wall_clock", Durations.format(budget.wallClock()));
        b.put("output_mb", budget.outputMb());
        return b;
    }

    private void putDepends(ObjectNode node, List<String> dependsOn) {
        if (!dependsOn.isEmpty()) {
            node.set("depends_on", strings(dependsOn));
        }
    }

    private void putStrings(ObjectNode node, String field, List<String> values) {
        if (!values.isEmpty()) {
            node.set(field, strings(values));
        }
    }

    private void putPaths(ObjectNode node, String field, List<Path> paths) {
        if (!paths.isEmpty()) {
            ArrayNode array = node.putArray(field);
            paths.forEach(p -> array.add(p.toString()));
        }
    }

    private ArrayNode strings(List<String> values) {
        ArrayNode array = mapper.createArrayNode();
        values.forEach(array::add);
        return array;
    }
}
