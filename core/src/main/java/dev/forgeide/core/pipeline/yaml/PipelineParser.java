package dev.forgeide.core.pipeline.yaml;

import com.fasterxml.jackson.databind.JsonNode;
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
import dev.forgeide.core.pipeline.validation.InvalidPipelineException;
import dev.forgeide.core.pipeline.validation.PipelineError;
import dev.forgeide.core.policy.FailPolicy;
import dev.forgeide.core.policy.RetryPolicy;
import dev.forgeide.core.policy.TokenBudget;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Maps a parsed YAML tree onto the domain model, collecting {@link PipelineError}s with
 * coordinates rather than throwing on the first problem. Structural problems are gathered
 * for the whole file and reported together via {@link InvalidPipelineException}; the model
 * is only constructed once the tree is clean.
 */
final class PipelineParser {

    /** Budget applied to agent steps that do not declare one (SDD §5.1). */
    private static final TokenBudget DEFAULT_BUDGET =
            new TokenBudget(2_000_000, Duration.ofMinutes(30), 512);
    private static final Duration DEFAULT_SCRIPT_TIMEOUT = Duration.ofMinutes(5);

    private final List<PipelineError> errors = new ArrayList<>();

    PipelineDefinition parse(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new InvalidPipelineException(List.of(
                    PipelineError.atPipeline("", "root must be a YAML mapping")));
        }
        String id = requireText(root, "", "id");
        int version = requireInt(root, "version");
        List<PipelineParam> params = parseParams(root.get("params"));
        List<StepDefinition> steps = parseSteps(root.get("steps"));

        if (!errors.isEmpty()) {
            throw new InvalidPipelineException(errors);
        }
        return new PipelineDefinition(id, version, params, steps);
    }

    // ---- params -----------------------------------------------------------------------

    private List<PipelineParam> parseParams(JsonNode node) {
        List<PipelineParam> params = new ArrayList<>();
        if (node == null || node.isNull()) {
            return params;
        }
        if (!node.isObject()) {
            errors.add(PipelineError.atPipeline("params", "params must be a mapping"));
            return params;
        }
        node.fields().forEachRemaining(entry -> {
            JsonNode spec = entry.getValue();
            boolean required = spec.path("required").asBoolean(false);
            Optional<String> hint = spec.hasNonNull("hint")
                    ? Optional.of(spec.get("hint").asText())
                    : Optional.empty();
            params.add(new PipelineParam(entry.getKey(), required, hint));
        });
        return params;
    }

    // ---- steps ------------------------------------------------------------------------

    private List<StepDefinition> parseSteps(JsonNode node) {
        List<StepDefinition> steps = new ArrayList<>();
        if (node == null || !node.isArray() || node.isEmpty()) {
            errors.add(PipelineError.atPipeline("steps", "steps must be a non-empty list"));
            return steps;
        }
        Set<String> ids = new java.util.HashSet<>();
        for (JsonNode stepNode : node) {
            StepDefinition step = parseStep(stepNode);
            if (step != null) {
                if (!ids.add(step.id())) {
                    errors.add(PipelineError.atStep(step.id(), "id", "duplicate step id"));
                }
                steps.add(step);
            }
        }
        return steps;
    }

    private StepDefinition parseStep(JsonNode node) {
        if (!node.isObject()) {
            errors.add(PipelineError.atPipeline("steps", "each step must be a mapping"));
            return null;
        }
        String id = requireText(node, "", "id");
        String type = requireText(node, id == null ? "" : id, "type");
        if (id == null || type == null) {
            return null;
        }
        List<String> dependsOn = textList(node.get("depends_on"), id, "depends_on");
        return switch (type) {
            case "agent" -> parseAgent(node, id, dependsOn);
            case "script" -> parseScript(node, id, dependsOn);
            case "judge" -> parseJudge(node, id, dependsOn);
            case "gate" -> parseGate(node, id, dependsOn);
            case "branch" -> parseBranch(node, id, dependsOn);
            case "per_task_loop" -> parsePerTaskLoop(node, id, dependsOn);
            case "outward" -> parseOutward(node, id, dependsOn);
            default -> {
                errors.add(PipelineError.atStep(id, "type", "unknown step type '" + type + "'"));
                yield null;
            }
        };
    }

    private StepDefinition parseAgent(JsonNode node, String id, List<String> dependsOn) {
        String runtime = requireText(node, id, "runtime");
        String prompt = requireText(node, id, "prompt");
        List<Path> expects = pathList(node.get("expects"), id, "expects");
        List<String> allowedWrite = textList(node.get("allowed_write"), id, "allowed_write");
        List<String> envScope = textList(node.get("env_scope"), id, "env_scope");
        TokenBudget budget = parseBudget(node.get("budget"), id);
        RetryPolicy retry = parseRetry(node.get("retry"), id);
        if (runtime == null || prompt == null) {
            return null;
        }
        return new AgentStep(id, dependsOn, runtime, Path.of(prompt), expects,
                allowedWrite, envScope, retry, budget);
    }

    private StepDefinition parseScript(JsonNode node, String id, List<String> dependsOn) {
        List<String> command = textList(node.get("command"), id, "command");
        if (command.isEmpty()) {
            errors.add(PipelineError.atStep(id, "command", "command must not be empty"));
            return null;
        }
        Duration timeout = parseDuration(node.get("timeout"), id, "timeout", DEFAULT_SCRIPT_TIMEOUT);
        return new ScriptStep(id, dependsOn, command, timeout);
    }

    private StepDefinition parseJudge(JsonNode node, String id, List<String> dependsOn) {
        String target = requireText(node, id, "target");
        Optional<ScriptStep> check = parseCheck(node.get("check"), id);
        Optional<AgentStep> llm = parseLlmJudge(node.get("llm"), id);
        FailPolicy failPolicy = parseFailPolicy(node.get("fail_policy"), id);
        if (target == null) {
            return null;
        }
        if (check.isEmpty() && llm.isEmpty()) {
            errors.add(PipelineError.atStep(id, "check", "judge requires a 'check' and/or 'llm' block"));
            return null;
        }
        // A judge always orders after its target even if depends_on omits it.
        List<String> effectiveDeps = withTarget(dependsOn, target);
        return new JudgeStep(id, effectiveDeps, target, llm, check, failPolicy);
    }

    private StepDefinition parseGate(JsonNode node, String id, List<String> dependsOn) {
        String question = requireText(node, id, "question");
        List<String> options = textList(node.get("options"), id, "options");
        List<Path> show = pathList(node.get("show"), id, "show");
        if (options.isEmpty()) {
            errors.add(PipelineError.atStep(id, "options", "gate requires at least one option"));
        }
        if (question == null || options.isEmpty()) {
            return null;
        }
        return new GateStep(id, dependsOn, question, options, show);
    }

    private StepDefinition parseBranch(JsonNode node, String id, List<String> dependsOn) {
        JsonNode routesNode = node.get("routes");
        if (routesNode == null || !routesNode.isObject() || routesNode.isEmpty()) {
            errors.add(PipelineError.atStep(id, "routes", "branch requires a non-empty routes mapping"));
            return null;
        }
        Map<String, String> routes = new LinkedHashMap<>();
        routesNode.fields().forEachRemaining(e -> routes.put(e.getKey(), e.getValue().asText()));
        return new BranchStep(id, dependsOn, routes);
    }

    private StepDefinition parsePerTaskLoop(JsonNode node, String id, List<String> dependsOn) {
        String taskPlan = requireText(node, id, "task_plan");
        JsonNode templateNode = node.get("template");
        List<StepDefinition> template = new ArrayList<>();
        if (templateNode == null || !templateNode.isArray() || templateNode.isEmpty()) {
            errors.add(PipelineError.atStep(id, "template", "per_task_loop requires a non-empty template"));
        } else {
            for (JsonNode nested : templateNode) {
                StepDefinition step = parseStep(nested);
                if (step != null) {
                    template.add(step);
                }
            }
        }
        if (taskPlan == null || template.isEmpty()) {
            return null;
        }
        return new PerTaskLoop(id, dependsOn, Path.of(taskPlan), template);
    }

    private StepDefinition parseOutward(JsonNode node, String id, List<String> dependsOn) {
        List<String> raw = textList(node.get("actions"), id, "actions");
        if (raw.isEmpty()) {
            errors.add(PipelineError.atStep(id, "actions", "outward requires at least one action"));
            return null;
        }
        List<OutwardAction> actions = new ArrayList<>();
        for (String action : raw) {
            try {
                actions.add(OutwardAction.valueOf(action.toUpperCase()));
            } catch (IllegalArgumentException ex) {
                errors.add(PipelineError.atStep(id, "actions", "unknown outward action '" + action + "'"));
            }
        }
        List<String> envScope = textList(node.get("env_scope"), id, "env_scope");
        if (actions.size() != raw.size()) {
            return null;
        }
        return new OutwardStep(id, dependsOn, actions, envScope);
    }

    // ---- nested value objects ---------------------------------------------------------

    private Optional<ScriptStep> parseCheck(JsonNode node, String judgeId) {
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        List<String> command = textList(node.get("command"), judgeId, "check.command");
        if (command.isEmpty()) {
            errors.add(PipelineError.atStep(judgeId, "check.command", "check requires a command"));
            return Optional.empty();
        }
        Duration timeout = parseDuration(node.get("timeout"), judgeId, "check.timeout", DEFAULT_SCRIPT_TIMEOUT);
        return Optional.of(new ScriptStep(judgeId + ".check", List.of(), command, timeout));
    }

    private Optional<AgentStep> parseLlmJudge(JsonNode node, String judgeId) {
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        String runtime = requireText(node, judgeId, "runtime", "llm.runtime");
        String prompt = requireText(node, judgeId, "prompt", "llm.prompt");
        if (runtime == null || prompt == null) {
            return Optional.empty();
        }
        TokenBudget budget = parseBudget(node.get("budget"), judgeId);
        return Optional.of(new AgentStep(judgeId + ".llm", List.of(), runtime, Path.of(prompt),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT, budget));
    }

    private TokenBudget parseBudget(JsonNode node, String stepId) {
        if (node == null || node.isNull()) {
            return DEFAULT_BUDGET;
        }
        long tokens = longValue(node.get("tokens"), stepId, "budget.tokens", DEFAULT_BUDGET.tokens());
        Duration wallClock = parseDuration(node.get("wall_clock"), stepId, "budget.wall_clock",
                DEFAULT_BUDGET.wallClock());
        long outputMb = longValue(node.get("output_mb"), stepId, "budget.output_mb", DEFAULT_BUDGET.outputMb());
        return new TokenBudget(tokens, wallClock, outputMb);
    }

    private RetryPolicy parseRetry(JsonNode node, String stepId) {
        if (node == null || node.isNull()) {
            return RetryPolicy.DEFAULT;
        }
        int stream = intOrDefault(node.get("stream"), RetryPolicy.DEFAULT.stream());
        int script = intOrDefault(node.get("script"), RetryPolicy.DEFAULT.script());
        return new RetryPolicy(stream, script);
    }

    private FailPolicy parseFailPolicy(JsonNode node, String stepId) {
        if (node == null || node.isNull()) {
            return FailPolicy.DEFAULT;
        }
        int max = intOrDefault(node.get("max_iterations"), FailPolicy.DEFAULT.maxIterations());
        if (max < 1) {
            errors.add(PipelineError.atStep(stepId, "fail_policy", "max_iterations must be >= 1"));
            return FailPolicy.DEFAULT;
        }
        return new FailPolicy(max);
    }

    // ---- primitives -------------------------------------------------------------------

    private String requireText(JsonNode parent, String stepId, String field) {
        return requireText(parent, stepId, field, field);
    }

    /** Reads {@code lookupField} but reports errors under {@code label} (for nested blocks). */
    private String requireText(JsonNode parent, String stepId, String lookupField, String label) {
        JsonNode value = parent.get(lookupField);
        if (value == null || !value.isValueNode() || value.asText().isBlank()) {
            errors.add(PipelineError.atStep(stepId, label, "missing required field '" + label + "'"));
            return null;
        }
        return value.asText();
    }

    private int requireInt(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isInt()) {
            errors.add(PipelineError.atPipeline(field, "missing or non-integer field '" + field + "'"));
            return 0;
        }
        return value.asInt();
    }

    private int intOrDefault(JsonNode node, int fallback) {
        return node != null && node.isInt() ? node.asInt() : fallback;
    }

    private long longValue(JsonNode node, String stepId, String field, long fallback) {
        if (node == null || node.isNull()) {
            return fallback;
        }
        if (node.isNumber()) {
            return node.asLong();
        }
        if (node.isTextual()) {
            try {
                return Long.parseLong(node.asText().replace("_", "").strip());
            } catch (NumberFormatException ex) {
                errors.add(PipelineError.atStep(stepId, field, "not a number: '" + node.asText() + "'"));
            }
        } else {
            errors.add(PipelineError.atStep(stepId, field, "expected a number"));
        }
        return fallback;
    }

    private Duration parseDuration(JsonNode node, String stepId, String field, Duration fallback) {
        if (node == null || node.isNull()) {
            return fallback;
        }
        try {
            return Durations.parse(node.asText());
        } catch (RuntimeException ex) {
            errors.add(PipelineError.atStep(stepId, field, "invalid duration: '" + node.asText() + "'"));
            return fallback;
        }
    }

    private List<String> textList(JsonNode node, String stepId, String field) {
        List<String> values = new ArrayList<>();
        if (node == null || node.isNull()) {
            return values;
        }
        if (!node.isArray()) {
            errors.add(PipelineError.atStep(stepId, field, "expected a list"));
            return values;
        }
        node.forEach(e -> values.add(e.asText()));
        return values;
    }

    private List<Path> pathList(JsonNode node, String stepId, String field) {
        return textList(node, stepId, field).stream().map(Path::of).toList();
    }

    private List<String> withTarget(List<String> dependsOn, String target) {
        if (dependsOn.contains(target)) {
            return dependsOn;
        }
        List<String> merged = new ArrayList<>(dependsOn);
        merged.add(target);
        return merged;
    }
}
