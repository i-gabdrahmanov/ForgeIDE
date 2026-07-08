package dev.forgeide.runtime.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.forgeide.core.audit.AuditEvent;
import dev.forgeide.core.run.RunLogLayout;
import dev.forgeide.core.run.RunSnapshot;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Bundles one run into a single archive for offline investigation or a bug report (SDD FR-7.8):
 * the persisted snapshot, the full audit hash-chain, and every raw per-iteration log directory
 * (each {@code meta.json} already carries the full prompt + its hash — see {@code
 * AbstractAgentRuntime#writeMeta} — which is what makes the phase reproducible from the export
 * alone). Deliberately takes already-loaded data rather than a {@code StateStore}, so it's a
 * pure "write this zip" operation, testable without a real state directory.
 *
 * <p>Lives in this package (not {@code ui}) to reuse {@link RunSnapshotCodec}/{@link
 * AuditEnvelopeCodec} directly, keeping the exported {@code run.json}/{@code audit.jsonl} shapes
 * byte-for-byte consistent with what {@link FileStateStore} itself writes.
 */
public final class RunExporter {

    private final ObjectMapper mapper;

    public RunExporter() {
        this(new ObjectMapper());
    }

    public RunExporter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @param staleWarning when {@code true}, {@code export-manifest.json} notes that {@code
     *                     ground/ai-logs/<feature>/} may since have been overwritten by a later
     *                     re-run of the same feature (that directory is not runId-scoped) — an
     *                     honest flag, not a fix, for the caller to set by comparing against
     *                     {@code StateStore#listRuns}.
     */
    public void exportRun(RunSnapshot snapshot, List<AuditEvent> auditEvents, Path projectRoot,
                           Path outputZip, boolean staleWarning) throws IOException {
        RunSnapshotCodec runCodec = new RunSnapshotCodec(mapper);
        AuditEnvelopeCodec auditCodec = new AuditEnvelopeCodec(mapper);

        try (OutputStream fileOut = Files.newOutputStream(outputZip);
             ZipOutputStream zip = new ZipOutputStream(fileOut)) {

            writeEntry(zip, "run.json", mapper.writeValueAsString(runJson(runCodec, snapshot)));

            StringBuilder auditJsonl = new StringBuilder();
            for (AuditEvent event : auditEvents) {
                auditJsonl.append(mapper.writeValueAsString(auditCodec.toNode(event))).append('\n');
            }
            writeEntry(zip, "audit.jsonl", auditJsonl.toString());

            writeEntry(zip, "export-manifest.json", manifestJson(snapshot, staleWarning).toPrettyString());

            Path logsRoot = RunLogLayout.featureLogRoot(projectRoot, snapshot.featureSlug());
            if (Files.isDirectory(logsRoot)) {
                copyDirectory(zip, logsRoot, "ground/ai-logs/" + snapshot.featureSlug());
            }
        }
    }

    private ObjectNode runJson(RunSnapshotCodec runCodec, RunSnapshot snapshot) {
        ObjectNode snapshotNode = runCodec.toNode(snapshot);
        byte[] canonical = CanonicalJson.canonicalBytes(snapshotNode);
        String checksum = "sha256:" + CanonicalJson.sha256Hex("", canonical);
        ObjectNode root = mapper.createObjectNode();
        root.put("checksum", checksum);
        root.set("snapshot", snapshotNode);
        return root;
    }

    private ObjectNode manifestJson(RunSnapshot snapshot, boolean staleWarning) {
        ObjectNode manifest = mapper.createObjectNode();
        manifest.put("runId", snapshot.runId().value());
        manifest.put("exportedAt", Instant.now().toString());
        if (staleWarning) {
            manifest.put("warning", "this is not the most recent run for feature '" + snapshot.featureSlug()
                    + "' — raw logs under ground/ai-logs/ may reflect a later re-run of the same feature");
        }
        return manifest;
    }

    private static void writeEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void copyDirectory(ZipOutputStream zip, Path root, String zipPrefix) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                zip.putNextEntry(new ZipEntry(zipPrefix + "/" + relative));
                Files.copy(path, zip);
                zip.closeEntry();
            }
        }
    }
}
