package dev.forgeide.runtime.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.forgeide.core.audit.AuditEvent;
import dev.forgeide.core.port.StateStore;
import dev.forgeide.core.run.RunHaltReason;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.RunSnapshot;
import dev.forgeide.core.run.RunStatus;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * File-backed {@link StateStore} (SD §4, SDD FR-7.1-7.3): one {@code <feature>/<runId>/}
 * directory per run under the supplied {@code stateRoot} (conventionally
 * {@code ~/.forgeide/state/<project-hash>/<pipeline>}), holding {@code run.json}
 * (tmp+rename, checksummed) and {@code audit.jsonl} (append-only hash-chain).
 *
 * <p>A run's directory is keyed by runId rather than only featureSlug: the hash chain must
 * be scoped to a single run, since two runs of the same feature (re-run/branch) writing into
 * one shared chain would interleave seq/prev values into something no longer verifiable.
 *
 * <p>Hash-chain computation lives here, not in the caller: {@link #appendAudit} ignores
 * whatever {@code seq}/{@code prev}/{@code hash} the given {@link AuditEvent} carries and
 * recomputes them from this store's own chain tip, so a caller never needs to know the
 * chain's current state to append to it.
 */
public final class FileStateStore implements StateStore {

    private static final String RUN_FILE = "run.json";
    private static final String AUDIT_FILE = "audit.jsonl";

    private final Path stateRoot;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RunSnapshotCodec runCodec = new RunSnapshotCodec(mapper);
    private final AuditEnvelopeCodec auditCodec = new AuditEnvelopeCodec(mapper);

    private final Map<RunId, Path> runDirs = new ConcurrentHashMap<>();
    private final Map<RunId, ChainTip> chainTips = new ConcurrentHashMap<>();
    private final Object auditLock = new Object();

    public FileStateStore(Path stateRoot) {
        this.stateRoot = stateRoot;
    }

    public static Path defaultRoot(String projectHash, String pipelineId) {
        return Path.of(System.getProperty("user.home"), ".forgeide", "state", projectHash, pipelineId);
    }

    @Override
    public void save(RunSnapshot snapshot) {
        Path dir = runDir(snapshot.featureSlug(), snapshot.runId());
        try {
            Files.createDirectories(dir);
            ObjectNode snapshotNode = runCodec.toNode(snapshot);
            byte[] canonical = CanonicalJson.canonicalBytes(snapshotNode);
            String checksum = "sha256:" + CanonicalJson.sha256Hex("", canonical);

            ObjectNode root = mapper.createObjectNode();
            root.put("checksum", checksum);
            root.set("snapshot", snapshotNode);

            Path target = dir.resolve(RUN_FILE);
            Path tmp = Files.createTempFile(dir, "run", ".json.tmp");
            try {
                Files.writeString(tmp, mapper.writeValueAsString(root), StandardCharsets.UTF_8);
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot save run snapshot: " + snapshot.runId(), e);
        }
        runDirs.put(snapshot.runId(), dir);
    }

    @Override
    public Optional<RunSnapshot> load(RunId runId) {
        Path dir = resolveRunDir(runId);
        if (dir == null) {
            return Optional.empty();
        }
        Path target = dir.resolve(RUN_FILE);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        RunSnapshot snapshot = readRunFile(target);
        AuditReadResult audit = readAuditFile(dir.resolve(AUDIT_FILE));
        if (!audit.chainValid()) {
            snapshot = new RunSnapshot(snapshot.runId(), snapshot.featureSlug(), RunStatus.STOPPED,
                    Optional.of(RunHaltReason.AUDIT_CHAIN), snapshot.steps());
        }
        return Optional.of(snapshot);
    }

    @Override
    public List<RunId> listRuns(String featureSlug) {
        Path featureDir = stateRoot.resolve(featureSlug);
        if (!Files.isDirectory(featureDir)) {
            return List.of();
        }
        try (var children = Files.list(featureDir)) {
            return children.filter(Files::isDirectory)
                    .filter(p -> Files.isRegularFile(p.resolve(RUN_FILE)))
                    .map(p -> new RunId(p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list runs for feature: " + featureSlug, e);
        }
    }

    @Override
    public void appendAudit(AuditEvent event) {
        Path dir = resolveRunDir(event.runId());
        if (dir == null) {
            throw new IllegalStateException("no run directory for " + event.runId()
                    + "; save() the run before appending audit events");
        }
        Path auditPath = dir.resolve(AUDIT_FILE);

        synchronized (auditLock) {
            ChainTip tip = chainTips.computeIfAbsent(event.runId(), id -> readChainTip(auditPath));
            long seq = tip.seq() + 1;
            byte[] payloadBytes = CanonicalJson.canonicalBytes(event.payload());
            String hash = CanonicalJson.sha256Hex(tip.hash(), payloadBytes);
            AuditEvent finalEvent = new AuditEvent(seq, event.ts(), event.runId(), event.stepId(),
                    event.iteration(), event.type(), event.payload(), tip.hash(), hash);
            try {
                String line = mapper.writeValueAsString(auditCodec.toNode(finalEvent));
                Files.writeString(auditPath, line + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot append audit event: " + event.runId(), e);
            }
            chainTips.put(event.runId(), new ChainTip(seq, hash));
        }
    }

    @Override
    public List<AuditEvent> loadAudit(RunId runId) {
        Path dir = resolveRunDir(runId);
        if (dir == null) {
            return List.of();
        }
        return readAuditFile(dir.resolve(AUDIT_FILE)).events();
    }

    // ---- internals ----

    private Path runDir(String featureSlug, RunId runId) {
        return stateRoot.resolve(featureSlug).resolve(runId.value());
    }

    private Path resolveRunDir(RunId runId) {
        Path cached = runDirs.get(runId);
        if (cached != null) {
            return cached;
        }
        if (!Files.isDirectory(stateRoot)) {
            return null;
        }
        try (var features = Files.list(stateRoot)) {
            List<Path> featureDirs = features.filter(Files::isDirectory).toList();
            for (Path featureDir : featureDirs) {
                Path candidate = featureDir.resolve(runId.value());
                if (Files.isDirectory(candidate)) {
                    runDirs.put(runId, candidate);
                    return candidate;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot resolve run directory: " + runId, e);
        }
        return null;
    }

    private RunSnapshot readRunFile(Path target) {
        JsonNode root;
        try {
            root = mapper.readTree(target.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read run.json: " + target, e);
        }
        JsonNode checksumNode = root.get("checksum");
        JsonNode snapshotNode = root.get("snapshot");
        if (checksumNode == null || !checksumNode.isTextual() || snapshotNode == null) {
            throw new StateCorruptionException("run.json missing checksum/snapshot: " + target);
        }
        byte[] canonical = CanonicalJson.canonicalBytes(snapshotNode);
        String expected = "sha256:" + CanonicalJson.sha256Hex("", canonical);
        if (!expected.equals(checksumNode.asText())) {
            throw new StateCorruptionException("run.json checksum mismatch: " + target);
        }
        return runCodec.fromNode(snapshotNode);
    }

    private ChainTip readChainTip(Path auditPath) {
        List<AuditEvent> events = readAuditFile(auditPath).events();
        if (events.isEmpty()) {
            return ChainTip.genesis();
        }
        AuditEvent last = events.get(events.size() - 1);
        return new ChainTip(last.seq(), last.hash());
    }

    private AuditReadResult readAuditFile(Path auditPath) {
        if (!Files.isRegularFile(auditPath)) {
            return new AuditReadResult(List.of(), true);
        }
        List<AuditEvent> events = new ArrayList<>();
        boolean parseOk = true;
        try (BufferedReader reader = Files.newBufferedReader(auditPath, StandardCharsets.UTF_8)) {
            while (true) {
                String line;
                try {
                    // A corrupted byte can break UTF-8 decoding itself, not just JSON syntax
                    // (SDD FR-7.3 acceptance: a flipped byte anywhere in the file must be caught).
                    line = reader.readLine();
                } catch (IOException e) {
                    parseOk = false;
                    break;
                }
                if (line == null) {
                    break;
                }
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonNode node = mapper.readTree(line);
                    events.add(auditCodec.fromNode(node));
                } catch (IOException | RuntimeException e) {
                    parseOk = false;
                    break;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read audit log: " + auditPath, e);
        }
        boolean chainValid = parseOk && AuditChain.verify(events);
        return new AuditReadResult(events, chainValid);
    }

    private record ChainTip(long seq, String hash) {
        static ChainTip genesis() {
            return new ChainTip(0, AuditChain.GENESIS_HASH);
        }
    }

    private record AuditReadResult(List<AuditEvent> events, boolean chainValid) {
    }
}
