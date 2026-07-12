package dev.forgeide.runtime.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.forgeide.core.port.AgentEvent;
import dev.forgeide.core.port.AgentInvocation;
import dev.forgeide.core.port.AgentResult;
import dev.forgeide.core.port.AgentRuntimeException;
import dev.forgeide.core.port.AgentRuntimePort;
import dev.forgeide.core.port.TokenUsage;
import dev.forgeide.core.secret.SecretMasker;
import dev.forgeide.runtime.process.ParsedLine;
import dev.forgeide.runtime.process.ProcessKillSignal;
import dev.forgeide.runtime.process.ProcessLaunchException;
import dev.forgeide.runtime.process.ProcessOutcome;
import dev.forgeide.runtime.process.ProcessRunner;
import dev.forgeide.runtime.process.ProcessSpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Shared execution for a headless agent CLI (SD §6): builds the process, streams stdout
 * through {@link StreamJsonEvents}, enforces the token budget in real time by killing the
 * process group the moment accumulated {@code usage} crosses it (via {@link
 * ProcessKillSignal}), and writes {@code meta.json} (SDD §5.4). Subclasses only decide the
 * argv (the prompt itself always travels via stdin, never as a command-line token — SD
 * §6.1).
 */
public abstract class AbstractAgentRuntime implements AgentRuntimePort {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProcessRunner processRunner;

    protected AbstractAgentRuntime() {
        this(new ProcessRunner());
    }

    protected AbstractAgentRuntime(ProcessRunner processRunner) {
        this.processRunner = processRunner;
    }

    protected abstract List<String> buildCommand(AgentInvocation invocation);

    @Override
    public final AgentResult execute(AgentInvocation invocation, Consumer<AgentEvent> onEvent)
            throws AgentRuntimeException {
        guardAgainstForgeideHome(invocation);

        try {
            Files.createDirectories(invocation.logDir());
        } catch (IOException e) {
            throw new AgentRuntimeException("cannot create log dir " + invocation.logDir(), e);
        }

        List<String> command = buildCommand(invocation);
        Path stdoutLog = invocation.logDir().resolve("stdout.jsonl");
        Path stderrLog = invocation.logDir().resolve("stderr.log");
        Path metaFile = invocation.logDir().resolve("meta.json");

        ProcessSpec spec = new ProcessSpec(invocation.workingDir(), command, invocation.env(),
                Optional.of(invocation.prompt()), invocation.timeout(), invocation.maxOutputBytes(),
                stdoutLog, stderrLog);

        AtomicLong inputTokens = new AtomicLong();
        AtomicLong outputTokens = new AtomicLong();
        AtomicBoolean sawUsage = new AtomicBoolean();
        AtomicReference<JsonNode> finalJson = new AtomicReference<>();
        AtomicLong stdoutBytes = new AtomicLong();

        Consumer<ParsedLine> onLine = line -> {
            stdoutBytes.addAndGet(approxLength(line));
            for (AgentEvent event : StreamJsonEvents.parse(line)) {
                onEvent.accept(event);
                if (event instanceof AgentEvent.Usage usage) {
                    sawUsage.set(true);
                    inputTokens.addAndGet(usage.usage().inputTokens());
                    outputTokens.addAndGet(usage.usage().outputTokens());
                    if (inputTokens.get() + outputTokens.get() > invocation.tokenBudget()) {
                        throw new ProcessKillSignal(
                                "token budget " + invocation.tokenBudget() + " exceeded");
                    }
                } else if (event instanceof AgentEvent.Result result) {
                    finalJson.set(result.finalJson());
                }
            }
        };

        Instant started = Instant.now();
        String headBefore = GitHead.read(invocation.workingDir()).orElse(null);

        ProcessOutcome outcome;
        try {
            outcome = processRunner.run(spec, onLine, line -> { });
        } catch (ProcessLaunchException e) {
            throw new AgentRuntimeException("cannot launch " + command, e);
        }

        Instant finished = Instant.now();
        String headAfter = GitHead.read(invocation.workingDir()).orElse(null);

        TokenUsage usage = sawUsage.get()
                ? new TokenUsage(inputTokens.get(), outputTokens.get())
                : TokenBudgetEstimator.estimate(invocation.prompt(), stdoutBytes.get(),
                        Duration.between(started, finished));

        writeMeta(metaFile, command, invocation, started, finished, outcome.exitCode(), usage, headBefore, headAfter);

        return new AgentResult(outcome.exitCode(), Optional.ofNullable(finalJson.get()), usage, stdoutLog);
    }

    private static long approxLength(ParsedLine line) {
        return switch (line) {
            case ParsedLine.Raw raw -> raw.line().length() + 1L;
            case ParsedLine.Json json -> json.node().toString().length() + 1L;
        };
    }

    /** Hard SR-1 gate: never spawn a process whose prompt/env could leak the state-store root. */
    private static void guardAgainstForgeideHome(AgentInvocation invocation) throws AgentRuntimeException {
        if (containsForgeideHome(invocation.prompt())) {
            throw new AgentRuntimeException("refusing to launch: prompt references a .forgeide path (SR-1)");
        }
        for (String value : invocation.env().values()) {
            if (containsForgeideHome(value)) {
                throw new AgentRuntimeException("refusing to launch: env value references a .forgeide path (SR-1)");
            }
        }
    }

    private static boolean containsForgeideHome(String text) {
        return text != null && text.contains(".forgeide");
    }

    private static void writeMeta(Path metaFile, List<String> command, AgentInvocation invocation, Instant started,
                                   Instant finished, int exitCode, TokenUsage usage, String headBefore,
                                   String headAfter) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode commandArray = root.putArray("command");
        command.forEach(commandArray::add);
        ArrayNode envKeys = root.putArray("env_keys");
        invocation.env().keySet().forEach(envKeys::add);
        // T27/SD §6.2: env values never reach the prompt through variable rendering (only
        // project/feature/params scopes resolve there), so this is a second line of defense —
        // e.g. a judge's accumulated_errors block quoting a value the source-side mask in
        // PipelineEngine somehow missed — not the primary guarantee.
        root.put("prompt", SecretMasker.mask(invocation.prompt(), invocation.env().values()));
        root.put("prompt_sha256", sha256Hex(invocation.prompt()));
        root.put("started", started.toString());
        root.put("finished", finished.toString());
        root.put("exit_code", exitCode);
        ObjectNode usageNode = root.putObject("usage");
        usageNode.put("input_tokens", usage.inputTokens());
        usageNode.put("output_tokens", usage.outputTokens());
        if (headBefore != null) {
            root.put("head_before", headBefore);
        } else {
            root.putNull("head_before");
        }
        if (headAfter != null) {
            root.put("head_after", headAfter);
        } else {
            root.putNull("head_after");
        }
        try {
            Files.writeString(metaFile, root.toPrettyString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // meta.json is a reproducibility/investigation aid, not load-bearing for the
            // step's own outcome — a failure to write it must not fail the phase.
        }
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
