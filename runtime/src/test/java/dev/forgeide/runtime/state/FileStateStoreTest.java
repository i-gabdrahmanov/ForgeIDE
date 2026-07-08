package dev.forgeide.runtime.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.forgeide.core.audit.AuditEvent;
import dev.forgeide.core.run.AuditRef;
import dev.forgeide.core.run.FailureReason;
import dev.forgeide.core.run.JudgeVerdict;
import dev.forgeide.core.run.PendingQuestion;
import dev.forgeide.core.run.QuestionType;
import dev.forgeide.core.run.RunHaltReason;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.RunSnapshot;
import dev.forgeide.core.run.RunStatus;
import dev.forgeide.core.run.StepSnapshot;
import dev.forgeide.core.run.StepStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FileStateStoreTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private FileStateStore store;

    @BeforeEach
    void setUp() {
        store = new FileStateStore(tempDir);
    }

    @Test
    void savedSnapshotRoundTripsThroughLoad() {
        RunId runId = RunId.newId();
        RunSnapshot snapshot = fullSnapshot(runId, "feat-round-trip");

        store.save(snapshot);

        assertThat(store.load(runId)).contains(snapshot);
    }

    @Test
    void loadOfUnknownRunIsEmpty() {
        assertThat(store.load(RunId.newId())).isEmpty();
    }

    @Test
    void listRunsReturnsAllRunsOfAFeature() {
        RunId r1 = RunId.newId();
        RunId r2 = RunId.newId();
        store.save(fullSnapshot(r1, "feat-list"));
        store.save(fullSnapshot(r2, "feat-list"));
        store.save(fullSnapshot(RunId.newId(), "other-feature"));

        assertThat(store.listRuns("feat-list")).containsExactlyInAnyOrder(r1, r2);
        assertThat(store.listRuns("does-not-exist")).isEmpty();
    }

    @Test
    void tamperedRunJsonChecksumIsRejectedOnLoad() throws IOException {
        RunId runId = RunId.newId();
        store.save(fullSnapshot(runId, "feat-tamper"));
        Path runFile = tempDir.resolve("feat-tamper").resolve(runId.value()).resolve("run.json");

        String content = Files.readString(runFile);
        String corrupted = content.replace("\"status\":\"PAUSED\"", "\"status\":\"COMPLETED\"");
        assertThat(corrupted).isNotEqualTo(content);
        Files.writeString(runFile, corrupted);

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(StateCorruptionException.class,
                () -> store.load(runId))).hasMessageContaining("checksum");
    }

    @Test
    void interruptedRunJsonWriteLeavesPreviousSnapshotIntact() throws IOException {
        RunId runId = RunId.newId();
        RunSnapshot v1 = fullSnapshot(runId, "feat-crash");
        store.save(v1);

        Path dir = tempDir.resolve("feat-crash").resolve(runId.value());
        // Simulate a process kill after the tmp file was written but before the atomic rename:
        // save() always writes to a fresh tmp file and only makes it visible via Files.move,
        // so a stray, half-written tmp file must never affect what load() returns.
        Path strayTmp = Files.createTempFile(dir, "run", ".json.tmp");
        Files.writeString(strayTmp, "{not even valid json");

        assertThat(store.load(runId)).contains(v1);
        assertThat(Files.readString(dir.resolve("run.json"))).doesNotContain("not even valid json");

        // A subsequent, properly completed save still works normally afterwards.
        RunSnapshot v2 = new RunSnapshot(runId, "feat-crash", RunStatus.COMPLETED, Optional.empty(), v1.steps());
        store.save(v2);
        assertThat(store.load(runId)).contains(v2);
    }

    @Test
    void appendAuditIgnoresCallerSuppliedChainFieldsAndComputesItsOwn() {
        RunId runId = RunId.newId();
        store.save(fullSnapshot(runId, "feat-chain"));

        store.appendAudit(fixtureEvent(runId, "step-a", 1, "step.started", "n1"));
        store.appendAudit(fixtureEvent(runId, "step-a", 1, "step.passed", "n2"));

        List<AuditEvent> events = store.loadAudit(runId);
        assertThat(events).hasSize(2);
        assertThat(events.get(0).seq()).isEqualTo(1);
        assertThat(events.get(1).seq()).isEqualTo(2);
        assertThat(events.get(0).prevHash()).isEmpty();
        assertThat(events.get(1).prevHash()).isEqualTo(events.get(0).hash());
        assertThat(AuditChain.verify(events)).isTrue();
    }

    @Test
    void corruptedByteInMiddleOfAuditLogStopsTheRunOnLoad() throws IOException {
        RunId runId = RunId.newId();
        store.save(fullSnapshot(runId, "feat-corrupt"));
        for (int i = 1; i <= 6; i++) {
            store.appendAudit(fixtureEvent(runId, "step-" + i, i, "step.passed", "note-" + i));
        }

        Path auditFile = tempDir.resolve("feat-corrupt").resolve(runId.value()).resolve("audit.jsonl");
        byte[] bytes = Files.readAllBytes(auditFile);
        int mid = bytes.length / 2;
        bytes[mid] = (byte) (bytes[mid] ^ 0xFF);
        Files.write(auditFile, bytes);

        Optional<RunSnapshot> loaded = store.load(runId);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().status()).isEqualTo(RunStatus.STOPPED);
        assertThat(loaded.get().haltReason()).contains(RunHaltReason.AUDIT_CHAIN);
    }

    @Test
    void tenThousandAuditEventsWriteAndVerifyUnderOneSecond() {
        RunId runId = RunId.newId();
        store.save(fullSnapshot(runId, "feat-perf"));

        long start = System.nanoTime();
        for (int i = 1; i <= 10_000; i++) {
            store.appendAudit(fixtureEvent(runId, "step", i, "step.passed", "n" + i));
        }
        List<AuditEvent> events = store.loadAudit(runId);
        boolean valid = AuditChain.verify(events);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(valid).isTrue();
        assertThat(events).hasSize(10_000);
        assertThat(elapsedMs).isLessThan(1000);
    }

    private RunSnapshot fullSnapshot(RunId runId, String featureSlug) {
        StepSnapshot waitingInput = new StepSnapshot(
                "lite-ground",
                StepStatus.WAITING_INPUT,
                2,
                Optional.empty(),
                List.of(new PendingQuestion("q1", "Which approach?", QuestionType.CHOICE,
                        List.of("a", "b"), Optional.of("docs/tech-design.md#approach"))),
                List.of(new JudgeVerdict(1, Optional.of(true), true, "ok")),
                List.of(new AuditRef(3, "step.failed")));
        StepSnapshot failed = new StepSnapshot(
                "lite-design", StepStatus.FAILED, 1, Optional.of(FailureReason.BUDGET),
                List.of(), List.of(new JudgeVerdict(1, Optional.empty(), false, "budget exceeded")), List.of());
        return new RunSnapshot(runId, featureSlug, RunStatus.PAUSED, Optional.of(RunHaltReason.ENGINE_ERROR),
                List.of(waitingInput, failed));
    }

    private AuditEvent fixtureEvent(RunId runId, String stepId, int iteration, String type, String note) {
        ObjectNode payload = mapper.createObjectNode().put("note", note);
        // seq/prevHash/hash below are deliberate garbage: FileStateStore must ignore them and
        // compute the real chain values itself.
        return new AuditEvent(-1, Instant.parse("2026-07-07T12:00:00Z"), runId, stepId, iteration, type,
                payload, "garbage-prev", "garbage-hash");
    }
}
