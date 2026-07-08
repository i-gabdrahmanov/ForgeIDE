package dev.forgeide.runtime.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.forgeide.core.audit.AuditEvent;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.RunSnapshot;
import dev.forgeide.core.run.RunStatus;
import dev.forgeide.core.run.StepSnapshot;
import dev.forgeide.core.run.StepStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

class RunExporterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RunExporter exporter = new RunExporter(mapper);

    @Test
    void archiveContainsSnapshotAuditAndRawLogsVerbatim(@TempDir Path projectRoot, @TempDir Path outDir)
            throws IOException {
        RunId runId = RunId.newId();
        RunSnapshot snapshot = snapshot(runId, "feature-x");
        List<AuditEvent> audit = List.of(auditEvent(runId, 1, "run.started"), auditEvent(runId, 2, "step.completed"));

        Path stepDir = projectRoot.resolve("ground").resolve("ai-logs").resolve("feature-x")
                .resolve("iter-1").resolve("lite-ground");
        Files.createDirectories(stepDir);
        Files.writeString(stepDir.resolve("stdout.jsonl"), "{\"type\":\"result\"}\n");
        Files.writeString(stepDir.resolve("meta.json"), "{\"prompt\":\"do the thing\"}");

        Path zipFile = outDir.resolve("export.zip");
        exporter.exportRun(snapshot, audit, projectRoot, zipFile, false);

        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            assertThat(entryNames(zip)).containsExactlyInAnyOrder(
                    "run.json", "audit.jsonl", "export-manifest.json",
                    "ground/ai-logs/feature-x/iter-1/lite-ground/stdout.jsonl",
                    "ground/ai-logs/feature-x/iter-1/lite-ground/meta.json");

            JsonNode runJson = mapper.readTree(readEntry(zip, "run.json"));
            assertThat(runJson.get("checksum").asText()).startsWith("sha256:");
            assertThat(runJson.get("snapshot").get("runId").asText()).isEqualTo(runId.value());

            String auditJsonl = readEntry(zip, "audit.jsonl");
            assertThat(auditJsonl.lines()).hasSize(2);
            assertThat(mapper.readTree(auditJsonl.lines().findFirst().orElseThrow()).get("type").asText())
                    .isEqualTo("run.started");

            JsonNode manifest = mapper.readTree(readEntry(zip, "export-manifest.json"));
            assertThat(manifest.get("runId").asText()).isEqualTo(runId.value());
            assertThat(manifest.has("warning")).isFalse();

            assertThat(readEntry(zip, "ground/ai-logs/feature-x/iter-1/lite-ground/meta.json"))
                    .isEqualTo("{\"prompt\":\"do the thing\"}");
        }
    }

    @Test
    void staleWarningIsIncludedWhenRequested(@TempDir Path projectRoot, @TempDir Path outDir) throws IOException {
        RunId runId = RunId.newId();
        Path zipFile = outDir.resolve("export.zip");

        exporter.exportRun(snapshot(runId, "feature-x"), List.of(), projectRoot, zipFile, true);

        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            JsonNode manifest = mapper.readTree(readEntry(zip, "export-manifest.json"));
            assertThat(manifest.get("warning").asText()).contains("feature-x");
        }
    }

    @Test
    void missingLogDirectoryIsSkippedNotAnError(@TempDir Path projectRoot, @TempDir Path outDir) throws IOException {
        RunId runId = RunId.newId();
        Path zipFile = outDir.resolve("export.zip");

        exporter.exportRun(snapshot(runId, "feature-x"), List.of(), projectRoot, zipFile, false);

        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            assertThat(entryNames(zip)).containsExactlyInAnyOrder("run.json", "audit.jsonl", "export-manifest.json");
        }
    }

    private static List<String> entryNames(ZipFile zip) {
        return zip.stream().map(ZipEntry::getName).toList();
    }

    private static String readEntry(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        assertThat(entry).as("zip entry " + name).isNotNull();
        try (InputStream in = zip.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private RunSnapshot snapshot(RunId runId, String featureSlug) {
        StepSnapshot step = new StepSnapshot("lite-ground", StepStatus.PASSED, 1,
                Optional.empty(), List.of(), List.of(), List.of());
        return new RunSnapshot(runId, featureSlug, RunStatus.COMPLETED, Optional.empty(), List.of(step));
    }

    private AuditEvent auditEvent(RunId runId, long seq, String type) {
        ObjectNode payload = mapper.createObjectNode();
        return new AuditEvent(seq, Instant.parse("2026-07-07T12:00:00Z"), runId, null, 0, type, payload, "", "");
    }
}
