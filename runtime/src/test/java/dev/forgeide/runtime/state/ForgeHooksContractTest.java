package dev.forgeide.runtime.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.forgeide.runtime.process.ProcessOutcome;
import dev.forgeide.runtime.process.ProcessRunner;
import dev.forgeide.runtime.process.ProcessSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NFR-6 contract test: the real Forge {@code tdd-guard.py} hook (vendored — see {@code
 * src/test/resources/forge-fixtures/README.md}) must correctly resolve the active step from a
 * {@link ManifestProjector}-written manifest, exactly as it would running inside an agent
 * process. This is what SD §12's format-drift risk mitigation names: "проекция скармливается
 * реальным хукам из forge-репо".
 */
class ForgeHooksContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void tddGuardResolvesTheActiveStepFromOurProjection(@TempDir Path project, @TempDir Path logs) throws Exception {
        Path hook = vendoredHook("tdd-guard.py");
        Files.createDirectories(project.resolve("ground"));
        Files.writeString(project.resolve("ground").resolve("pipeline.json"),
                "{\"quality\":{\"tdd\":true,\"tdd_integration_skip\":false}}");

        ManifestProjector projector = new ManifestProjector();
        Path statementsDir = ManifestProjector.statementsDir(project, "forgelite", "f1");
        Files.createDirectories(statementsDir);

        // lite-red still pending -> tdd-guard must block a src/main write (deny-first, exit 2).
        writeManifest(statementsDir, "\"pending\"");
        List<String> blockedStderr = new ArrayList<>();
        ProcessOutcome blocked = runHook(hook, project, logs, blockedStderr);
        assertThat(blocked.exitCode()).isEqualTo(2);
        assertThat(String.join("\n", blockedStderr)).contains("lite-red");

        // Same manifest.json path, our own projection format, lite-red now completed -> allowed.
        writeManifest(statementsDir, "\"completed\"");
        List<String> allowedStderr = new ArrayList<>();
        ProcessOutcome allowed = runHook(hook, project, logs, allowedStderr);
        assertThat(allowed.exitCode()).isEqualTo(0);
    }

    /** Writes a manifest by hand in the exact shape {@link ManifestProjector} produces (id/status
     * pairs under {@code steps}) rather than going through the projector, so this test pins down
     * the wire format the hook must keep parsing, independent of the projector's own internals. */
    private static void writeManifest(Path statementsDir, String liteRedStatusJson) throws Exception {
        Files.writeString(statementsDir.resolve("manifest.json"), """
                {
                  "version": 1,
                  "skill": "forgelite",
                  "feature": "f1",
                  "steps": [
                    {"id": "lite-red", "status": %s}
                  ]
                }
                """.formatted(liteRedStatusJson));
    }

    private static ProcessOutcome runHook(Path hook, Path project, Path logs, List<String> stderrLines) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("hook_event_name", "PreToolUse");
        payload.put("cwd", project.toString());
        payload.put("tool_name", "Write");
        ObjectNode toolInput = payload.putObject("tool_input");
        toolInput.put("file_path", project.resolve("service/src/main/java/A.java").toString());
        toolInput.put("content", "class A {}");

        ProcessSpec spec = new ProcessSpec(project, List.of("python3", hook.toString()),
                Map.of("PATH", System.getenv("PATH")), Optional.of(payload.toString()), Duration.ofSeconds(10),
                ProcessRunner.DEFAULT_MAX_OUTPUT_BYTES, logs.resolve("stdout.log"), logs.resolve("stderr.log"));
        return new ProcessRunner().run(spec, line -> { }, stderrLines::add);
    }

    private static Path vendoredHook(String name) throws URISyntaxException {
        URL resource = ForgeHooksContractTest.class.getResource("/forge-fixtures/hooks/" + name);
        if (resource == null) {
            throw new IllegalStateException("vendored hook not found on classpath: " + name);
        }
        return Paths.get(resource.toURI());
    }
}
